1. use an LLM to migrate payam branch to your new app (Ask it to rename packages and strings that match your new project )
2. Modify email templates

**NB**
*  Non-profile files (application.yaml, application.properties) share the same priority tier, within which application.properties from test resources wins — so the ${mtn.collection-base-url}/token/ expression is used, token URL → WireMock → test passes.
- Profile-specific files (application-dev.yaml) always have higher priority than any non-profile file, including src/test/resources/application.properties.

# Profiles
* It is important to note the presence of application.properties file in the test resources folder
* It overrides the application.yaml file for tests

## Decoupling LGTM Stack from Tests
The LGTM stack (Loki, Grafana, Tempo, Mimir/Prometheus) is used for observability in production and development but can cause tests to fail if not available. To ensure tests are portable and do not depend on the LGTM docker stack, the following properties are configured in `src/test/resources/application.properties`:

* `logging.config=classpath:logback-test.xml`: Overrides the default `logback-spring.xml` (which includes the Loki appender) with a minimal test configuration.
* `management.tracing.enabled=false`: Disables distributed tracing.
* `management.otlp.tracing.export.enabled=false`: Prevents the OTLP exporter from attempting to connect to Tempo.
* `management.metrics.export.prometheus.enabled=false`: Disables the Prometheus metrics endpoint export during tests.

This allows `mvn verify` to pass without running `docker compose -f docker-compose-lgtm.yaml up`.

* **NB** You should put the application.properties file in your test resources folder and it will affect only tests
* **NB**  application.properties overwrites ONLY application.yaml. If you have a specific env config, it overrides the default configs


## Understanding Wiremock
* To start with, the application.properties files overwrites the application.yaml file
* The application.properties file has overwritten properties like `mtn.collection-token-url` and `orange.token-url`

```properties
mtn.collection-token-url=${mtn.collection-base-url}/token/
orange.token-url=${orange.base-url}/token
```

* Above you see `mtn.collection-token-url` is assigned a new value with a new placeholder `${mtn.collection-base-url}`
* Below you can see all the properties assigned the value of the wiremock base url
* The mockserver called "orange" replaces "orange.base-url", and  "orange.pay-url" with its base url  (in this case, http://localhost:CURRENT_PORT_NUMBER)
* The mockserver called "mtn" replaces "mtn.collection-base-url" with its base url
* This implies that `mtn.collection-token-url` has the following value `http://localhost:PORT_NUM/token`

```java
@EnableWireMock({
    @ConfigureWireMock(name = "orange", baseUrlProperties = {"orange.base-url", "orange.pay-url"}),
    @ConfigureWireMock(name = "mtn",    baseUrlProperties = {"mtn.collection-base-url"})
})
class FraudEngineIT {

    @InjectWireMock("mtn")
    WireMockServer mtnServer;

    @InjectWireMock("orange")
    WireMockServer orangeServer;

```