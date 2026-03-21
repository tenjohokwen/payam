# Security Module Architecture

## Overview

The security module lives under `com.softropic.payam.security` and follows a layered package architecture designed to eliminate circular dependencies, enforce unidirectional dependency flow, and make the role of every class immediately clear from its location.

The guiding principle: **a package's location is its specification**. You should be able to determine what a class is allowed to import — and what is allowed to import it — purely from the package it lives in.

---

## Package Structure

```
security/
├── api/              Entry points: controllers and facades
├── contract/         DTOs, enums, events, exceptions, value objects
├── service/          Domain business logic
├── infrastructure/   Implementation details (JWT, auditing, handlers)
│   ├── audit/
│   ├── filter/       Security filters (SecondFactorLoginFilter, SecurityAdviceFilter, SessionRefreshFilter)
│   ├── listener/     Spring event listeners (auth, fraud, mail)
│   └── jwt/
│       └── filter/   JWT-specific filters (JWTAuthenticationFilter, JWTAuthorizationFilter)
├── repo/             JPA entities and Spring Data repositories
├── common/           Internal cross-cutting utilities and events
├── config/           Spring wiring and configuration
└── audit/            Audit trail (sub-module, pending migration to infrastructure)
```

---

## Package Conventions

### Dependency Flow

```
api → service → repo
 ↓       ↓       ↓
      contract ←──
         ↑
   (accessible from everywhere)

infrastructure → service
             └→ repo
             └→ contract
```

Dependencies only flow **downward or toward `contract`**. No package may import from a package above it in this hierarchy.

---

### Package Roles and Access Rules

#### `api`
**Role:** Entry points into the module. Controllers (`@RestController`) and facades that orchestrate service calls and translate between the web layer and domain logic.

**Allowed imports:** `service`, `contract`, `repo`

**Prohibited imports:** `infrastructure`, `config`, `core`, `manager`

**Examples:** `AccountResource`, `ProfileResource`, `AccountManagementFacade`

---

#### `contract`
**Role:** The shared language of the module. Contains only passive types: DTOs, enums, events, exceptions, and value objects. Has zero logic that depends on Spring or other infrastructure. Can be safely imported from any layer without creating a dependency cycle.

**Allowed imports:** nothing within the security module (may import standard Java and third-party libraries only)

**Prohibited:** any Spring stereotype annotation (`@Component`, `@Service`, `@Bean`, `@Repository`, `@Controller`, `@Configuration`). The only permitted Spring annotation is `@ConfigurationProperties`, which is a binding metadata annotation, not a bean stereotype.

**Sub-packages:**
- `contract/event/` — Spring application events published across layers
- `contract/exception/` — all checked and unchecked exceptions
- `contract/util/` — stateless utility classes and constants

**Examples:** `Principal`, `UserDto`, `LoginIdType`, `SecurityError`, `AuthorizationException`, `AccountChangeEvent`, `PermutedSecretKey`, `LoginData`, `SecurityProperties`

---

#### `service`
**Role:** Domain business logic. Services own use-case orchestration: they read from `repo`, apply business rules, publish events, and return `contract` types.

**Allowed imports:** `repo`, `contract`, `common`

**Prohibited imports:** `infrastructure`, `api`, `config`

**Examples:** `UserService`, `LoginInfoService`, `TwoFactorLoginService`, `PasswordResetService`, `SecretService`, `SecKeyService`, `SecurityUtil`, `LoginTokenManager` (interface), `LoginDecisionManager` (interface), `LoginAttemptConsumer` (interface), `LoginAttemptsService`, `ClientIdAccessDecisionManager`

> **Note on `ClientIdAccessDecisionManager`:** Although it implements Spring Security's `AuthorizationManager<RequestAuthorizationContext>`, its core logic (checking an allowed-client list against request metadata) is a domain/business decision. It lives in `service/` rather than `infrastructure/` because `LoginAttemptsService` (also in `service/`) depends on it directly. Moving it to `infrastructure/` would create a prohibited `service → infrastructure` dependency.

---

#### `infrastructure`
**Role:** Technical implementation details that fulfill service interfaces. JWT token management, JPA auditing, security handlers. These classes know *how* things are done; `service` classes know *what* needs to be done.

**Allowed imports:** `service`, `repo`, `contract`, `common`

**Prohibited imports:** `api`

**Sub-packages:**
- `infrastructure/audit/` — JPA auditing (`SpringSecurityAuditorAware`)
- `infrastructure/filter/` — General security filters (`SecurityAdviceFilter`, `SecondFactorLoginFilter`, `SessionRefreshFilter`)
- `infrastructure/listener/` — Spring event listeners (`AuthenticationFailureListener`, `AuthenticationSuccessListener`, `SendMailListener`, etc.)
- `infrastructure/jwt/` — JWT token creation, validation, extraction
- `infrastructure/jwt/filter/` — JWT-specific filters (`JWTAuthenticationFilter`, `JWTAuthorizationFilter`)

**Examples:** `JwtManagerImpl`, `JwtConfiguration`, `ClaimsExtractorImpl`, `TokenCreatorImpl`, `JWTAuthenticationFilter`, `JWTAuthorizationFilter`, `SpringSecurityAuditorAware`, `AjaxLogoutSuccessHandler`, `FraudAwareAuthenticationManager`, `AuthenticationManagerSimulator`, `UnanimousAuthorizationManager`, `SecuredHttpEndpointGuard`

---

#### `repo`
**Role:** Leaf node. JPA entities, Spring Data repository interfaces, JPA entity listeners, and value objects whose lifecycle is tied directly to an entity. Nothing above this layer may be imported here.

**Allowed imports:** `contract` only

**Prohibited imports:** everything else within the security module

**Examples:** `User`, `LoginInfo`, `Authority`, `SecKey`, `Secret`, `UserRepository`, `LoginInfoRepository`, `SecKeyRepository`, `SecKeyEntityListener`

---

#### `common`
**Role:** Internal cross-cutting utilities shared across layers that are too tightly coupled to this module to live in a general `common` module. Event types for internal Spring events, cookie utilities, security constants.

**Allowed imports:** `contract` only

**Prohibited imports:** `service`, `infrastructure`, `repo`, `api`

**Sub-packages:**
- `common/event/` — internal Spring application events (`AuthEvent`, `PreAuthEvent`, `BadCredentialsEvent`, `FraudEvent`)
- `common/util/` — stateless utilities (`CookieUtil`, `SecurityConstants`)

---

#### `config`
**Role:** Spring composition root. Wires all beans together. `@Configuration` classes here are the only place permitted to import from `infrastructure`, `service`, `manager`, and `core` simultaneously. This is intentional — the composition root must see everything it wires.

**Note:** `config` is exempt from the one-directional import rule because it is the wiring layer, not a business layer.

**Examples:** `SecurityConfiguration`, `CorsConfig`, `MvcConfig`, `AppEndpoints`

---

## Class Placement Guide

This section answers "where does this type of class go?" for common cases.

### DTOs and Request/Response Objects
**→ `contract/`**

Any plain Java object that carries data across layer boundaries. No Spring annotations, no business logic.

```
contract/ChangePasswordDto.java
contract/UserDto.java
api/dto/AddressDto.java          ← API-specific DTOs that never leave the api layer stay in api/dto
```

> **Rule:** If a DTO is only used within `api` (never passed to `service`), it may live in `api/dto/`. If it crosses into `service`, it belongs in `contract/`.

---

### Exceptions
**→ `contract/exception/`**

All exceptions, whether thrown by `service`, `repo`, or `infrastructure`. Since exceptions are caught and handled across layers, they must be importable from everywhere — which only `contract` guarantees.

```
contract/exception/UserNotFoundException.java
contract/exception/JWTExpiredException.java
contract/exception/AuthorizationException.java
```

---

### Spring Application Events
**→ `contract/event/`** for events crossing layer boundaries
**→ `common/event/`** for events used only within internal infrastructure wiring

```
contract/event/AccountChangeEvent.java     ← published by service, consumed by listeners in other layers
common/event/AuthEvent.java                ← internal event between security filters and listeners
common/event/PreAuthEvent.java
```

> **Rule:** If an event is published in one layer and consumed in another (e.g., `service` → `audit.listener`), it belongs in `contract/event/`. If it is produced and consumed entirely within the security module's own filter/listener chain, it belongs in `common/event/`.

---

### Enums and Constants
**→ `contract/`** for enums used across layers
**→ `common/util/SecurityConstants.java`** for string/numeric constants used only within the security module's infrastructure

```
contract/LoginIdType.java          ← used in api, service, and infrastructure
contract/util/AuthoritiesConstants.java
common/util/SecurityConstants.java ← JWT cookie names, claim keys — internal wiring detail
```

---

### Service Interfaces
**→ `service/`**

Interfaces that define capability boundaries (what the system can do, not how). Implementations live in `infrastructure/` or `service/` depending on whether they are technical or domain concerns.

```
service/LoginTokenManager.java     ← interface; implemented by infrastructure/jwt/JwtManagerImpl.java
```

---

### JPA Entities
**→ `repo/`**

All `@Entity` and `@Embeddable` classes.

```
repo/User.java
repo/LoginInfo.java
repo/SecKey.java
```

---

### Spring Data Repositories
**→ `repo/`** alongside their entity

```
repo/UserRepository.java         ← lives next to repo/User.java
repo/SecKeyRepository.java
```

---

### JPA Entity Listeners
**→ `repo/`** alongside their entity

Entity listeners are tightly coupled to a specific entity's lifecycle. They belong in the same package as the entity, not in `infrastructure` or `service`.

```
repo/SecKeyEntityListener.java   ← registered via @EntityListeners on repo/SecKey.java
```

> **Important:** Entity listeners must not import from `service` or `infrastructure`. If an entity listener needs Spring-managed beans, use `SpringBeanAutowiringSupport` or a static `ApplicationContext` holder — it must not receive a service via constructor injection.

---

### JPA Projection Interfaces
**→ `contract/`**

Projection interfaces returned by repository query methods are part of the module's data contract. Since they are returned to `service` callers, they must be importable without importing `repo`.

```
contract/LoginData.java    ← used as return type in LoginInfoRepository queries
```

> **Rule:** A projection interface belongs in `contract/`, not in `repo/`. Placing it in `repo/` would force `service` to import `repo` for a type that is conceptually a read model, not a persistence concern.

---

### Value Objects Used Across Layers
**→ `contract/`**

A value object that is created in one layer and consumed in another (e.g., created in `infrastructure`, used in `service`, or used as a method parameter across both) must live in `contract/`.

```
contract/PermutedSecretKey.java   ← created by secret domain logic, used in repo entity listeners and services
contract/Principal.java           ← created in infrastructure filters, consumed throughout service and api
```

---

### Value Objects Internal to a Single Package
**→ stay in that package**

If a value object is only ever used within one package and never crosses a layer boundary, it stays in its package. Do not promote it to `contract/` preemptively.

```
secret/SecKeyService.java         ← only used within the secret sub-domain
```

---

### `@ConfigurationProperties` Classes
**→ `contract/`**

Configuration properties POJOs are passive value holders — they have no behaviour and no Spring bean lifecycle. They belong in `contract/`.

Strip `@Configuration` from the class itself and register it via `@EnableConfigurationProperties(Xyz.class)` on a `@Configuration` class in `config/`.

```
contract/SecurityProperties.java          ← has @ConfigurationProperties only, no @Configuration
config/SecurityConfiguration.java        ← has @EnableConfigurationProperties(SecurityProperties.class)
```

> **Why:** `@Configuration` makes the class a Spring bean, which violates the "no Spring beans in `contract`" rule. `@ConfigurationProperties` alone is pure metadata for the property binding framework — it does not register the class as a bean.

---

### Infrastructure Implementations of Service Interfaces
**→ `infrastructure/`** or an appropriate sub-package

When an interface is defined in `service/` and its implementation is a technical detail (JWT, encryption, external API), the implementation lives in `infrastructure/`.

```
service/LoginTokenManager.java              ← interface (defines the contract)
infrastructure/jwt/JwtManagerImpl.java      ← implementation (JWT-specific detail)
```

---

### Spring Security Filters
**→ `infrastructure/jwt/filter/`**

Filters are infrastructure — they translate HTTP concerns into domain events or authentication tokens. They are not business logic.

```
infrastructure/jwt/filter/JWTAuthenticationFilter.java
infrastructure/jwt/filter/JWTAuthorizationFilter.java
```

---

### Audit Trail Infrastructure
**→ `infrastructure/audit/`**

JPA auditing hooks and Spring Security auditor providers.

```
infrastructure/audit/SpringSecurityAuditorAware.java
```

---

## Packages Pending Further Consolidation

The following packages predate the current convention and have not yet been fully migrated. New code should not be added to them; instead, place new classes in the appropriate canonical package.

| Package | Current Contents | Target |
|---|---|---|
| `audit/` | Audit trail service, listeners, repository, events | `infrastructure/audit/`, `repo/`, `contract/event/` |

---

## Dependency Rule Enforcement Checklist

When adding or moving a class, verify:

- [ ] `repo/` classes import **only** from `contract/` and within `repo/`
- [ ] `common/` classes import **only** from `contract/` and within `common/`
- [ ] `service/` classes import **only** from `repo/`, `contract/`, `common/`, and within `service/`
- [ ] `infrastructure/` classes import **only** from `service/`, `repo/`, `contract/`, `common/`
- [ ] `api/` classes import **only** from `service/`, `contract/`, `repo/`
- [ ] `contract/` classes carry **no** Spring stereotype annotations (`@Component`, `@Service`, `@Bean`, `@Repository`, `@Controller`, `@Configuration`)
- [ ] Entity listeners live in `repo/` and import only from `contract/` and `repo/`
- [ ] Projection interfaces live in `contract/`, never in `repo/`
- [ ] `@ConfigurationProperties` classes live in `contract/` with `@Configuration` stripped; registered via `@EnableConfigurationProperties` in `config/`
