# Check Points

## Before Deploying to Production
* [ ] Before deploying to production, ensure that all features are complete and tested.
* [ ] Check that the application is secure and free of vulnerabilities (Do an OWASP check).
* [ ] Enumerate all API methods and then limit their access. (Use the @PreAuthorize annotation with the least roles allowed)
* [ ] Verify that the application is performant and meets the required performance benchmarks.
* [ ] Test the application in a staging environment to catch any last-minute issues.
* [ ] Double-check that all environment variables are set correctly for production. (see application-dev.yaml)
* [ ] Create secret key for JWT auth and insert into db (e.g. insert into main.sec (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, bus_id, value, version) values ('659287191260154475',	'SYSTEM_ACCOUNT',	'2024-12-24 06:51:55.357352',	'SYSTEM_ACCOUNT',	'2024-12-24 06:51:55.357352',	'bed78f34-3e09-4fa8-81db-32326a528cca',	null,	'ACTIVE',	'jot',	'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1');)
* [ ] Check that the application is free of any hardcoded credentials or sensitive information.
* [ ] ensure email config is set correctly in application.yaml and mails are sent successfully.
* [ ] LOG-OBS-01: Loki alerting rules for ERROR rate thresholds — configure in Grafana, not application code
* [ ] LOG-OBS-02: Custom Grafana dashboards for payment business events — built once sufficient log volume exists in production
* [ ] Ensure all Controllers have @Observable annotation
* [ ] Ask AI to determine the grafana dashboards needed for this app. 
* [ ] configure log time to be UTC
* [ ] check for console errors as you navigate the pages in the UI
* [ ] Ensure that sent email content is not logged
* [ ] Ensure application readiness dashboard is showing that app is up (redis accessibility must also be included)
* [ ] Run manual tests to ensure that the ledger is registering payments correctly
* [ ] Ensure observability with client requests. (may have to modify RestRequestInterceptor)
* [ ] Ensure observability server URLs are configured for prod (the server URL of each LGTM component)
* [ ] Ensure redis server, postgres and LGTM servers are up
* [ ] Turn on HTTPS
* [ ] Ensure you have scripts that clean up the various tables (AI should write them and also write tests that clean up mid way and continue inserting data and verify that data well inserted and nothing is lost)
* [ ] prepare the following env variables

    1 export SPRING_MAIL_PASSWORD="your_actual_spring_mail_password"
    2 export GMX_PASSWORD="your_actual_gmx_password"
    3 export GMAIL_PASSWORD="your_actual_gmail_password"
    4 export MAIL_DE_PASSWORD="your_actual_mail_de_password"
    5 export MOMO_SUBSCRIPTION_KEY="your_actual_momo_subscription_key"
    6 export LOKI_API_KEY=="loki key"


ISSUES
* When a user registers, he should be assigned the ROLE_USER authority
* mask out sensitive data from logs (tips: https://www.baeldung.com/logback-mask-sensitive-data, )
* clean up logging
* create missing migration scripts
* ensure constraints are created in the entities as well as migration scripts (Also ask AI to make suggestions for constraints on a module by module basis)
* Ask AI if with the current API you can do B2C and B2B transactions for both MTN and Orange
* Ask AI to ensure that 
    1. Data that is loaded from the DB should always have a limit. (pagination could be used where it makes sense)
  2. Jobs should always take into account that they will run in a multi-node environment so locks like select for update need to be used (with skip)
  3. The data loaded within a transaction should be limited. Pageable/pagination does not solve the problem because the data will remain in the transaction context
* Add the following to the hikari section of all apps
  connection-init-sql: "SET TIME ZONE 'UTC'"  # Ensure PostgreSQL session timezone is UTC for all connections.
* Ask AI to go through backend code and ensure it will maintain ACID properties when run in a multi threaded and multi node environment and without dead locks
* When creating a tenant, it should not be possible to have more than 1 tenant with the same name. The name should be unique and the error should be elegantly handled and displayed to the user attempting to create the tenant in the UI




**NB**
┌──────────────────────────────┬───────────────────────────────────────────────────────────────────────────────────────────────┬───────────────────────────────────────────────┐                                                                                                                                     
│            Layer             │                                         What it does                                          │     Effect on PostgreSQL session timezone     │                                                                                                                                     
├──────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────┤                                                                                                                                     
│ TimeZone=UTC in JDBC URL     │ Configures the driver's Calendar used when formatting java.sql.Timestamp → String on the wire │ None — no SET TIME ZONE is sent to the server │                                                                                                                                     
├──────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────┤
│ hibernate.jdbc.time_zone=UTC │ Uses a UTC Calendar when Hibernate calls setTimestamp(i, ts, cal)                             │ None — same driver-side only                  │                                                                                                                                     
├──────────────────────────────┼───────────────────────────────────────────────────────────────────────────────────────────────┼───────────────────────────────────────────────┤                                                                                                                                     
│ SET TIME ZONE 'UTC' (new)    │ Issues SET TIME ZONE 'UTC' to PostgreSQL at connection creation                               │ Sets the server-side session timezone to UTC  │                                                                                                                                     
└──────────────────────────────┴───────────────────────────────────────────────────────────────────────────────────────────────┴───────────────────────────────────────────────┘

Without the new line, PostgreSQL's NOW() and any explicit TIMESTAMP WITHOUT TIME ZONE casts used the server's OS timezone (your UTC+2). Hibernate parameters came in UTC. The SQL comparison last_modified_date (12:xx UTC+2) < cutoff (09:xx UTC) was always false by exactly 2 hours, regardless of how much you   
backdated.

With connection-init-sql, all three write paths now agree on UTC:
- @LastModifiedDate (Hibernate) → UTC
- jdbcTemplate raw SQL (NOW() - INTERVAL '3 minutes') → UTC (session is UTC)  //in practise this is flaky. There is no guarantee that UTC is taken. Simply avoid using postgres'/jdbc's "NOW"
- JPQL cutoff parameter (Hibernate) → UTC                                   
      

## Requirements
1. endpoint to abort a payment
2. endpoint to find the state of a payment (Currently, there is no direct merchant-facing endpoint to query the status of a payment.)
3. endpoint to submit merchant webhook
4. An admin should be able to manage tenants from the UI (define what is allowed. Which fields can be edited -- /v1/admin/tenants TenantAdminResource)
5. Ensure all resources (Controllers) are secured with the needed rights
6. Ask AI to document a way to investigate txns given the auditing, tracing etc that exist on the platform. Also ask if something could be added to the already existing stuff



## Documentation
* What are the benefits of EventLogService and how as well as for what reasons can it be queried?


## add to template project
* Connection Timeout: hikari.connection-timeout is set to 50,000ms (50s). This is very high. In a high-traffic environment, threads should fail faster when the pool is exhausted so the system can recover. Standard practice is usually 30s or less.

