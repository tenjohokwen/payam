# Check Points

## Before Deploying to Production
* [ ] Before deploying to production, ensure that all features are complete and tested.
* [ ] Check that the application is secure and free of vulnerabilities (Do an OWASP check).
* [ ] Verify that the application is performant and meets the required performance benchmarks.
* [ ] Test the application in a staging environment to catch any last-minute issues.
* [ ] Double-check that all environment variables are set correctly for production. (see application-dev.yaml)
* [ ] Create secret key for JWT auth and insert into db (e.g. insert into main.sec (id, created_by, created_date, last_modified_by, last_modified_date, request_id, session_id, status, bus_id, value, version) values ('659287191260154475',	'SYSTEM_ACCOUNT',	'2024-12-24 06:51:55.357352',	'SYSTEM_ACCOUNT',	'2024-12-24 06:51:55.357352',	'bed78f34-3e09-4fa8-81db-32326a528cca',	null,	'ACTIVE',	'jot',	'loiI8oT2C1tWecrNXPDjN8fveYEU8rD6nb1k1NbVy92rwdd4/KO+aHhXh3A5zjsT5eSFL/xI+9Rqyj4RI6QCiFywn5nZLIwHGPNEY0F9lnDnGGmVjv/9rO5fgGt83+cxNDyGoCePaVEpBd7xHxyDdfpAoLxQs8mhKGqcEsh09Q+26qEiEm/a9bgDSbSQ0sX00VHBLd35OLmvN+ydjEluYxBTa6KzGb2CQ6Ttg4ZaELmbZOWpEjQ1Z7BbbYiXmWyaY+2HnkyhONoGbUpvVKl1c4e9IlQzeUYkekbUbADIm2LNK9Nhfv5/L5esvFrdVOUcUpLk/y8UT9f5xOMLFJ4Ct6s0eTKvNqYkSz2DFRI8Ip4p/ns6gA4V/1MUf9GeqPUWLiOa28Vw15+R8ycUMqb8NZHOP1oj9RunhSwA7EY84bZL3+yePc3n1b8ne8xzaYVEdK1WBu3J6s2AoBaOL/JLWfu8MuxXI+ub', 'v1');)
* [ ] Check that the application is free of any hardcoded credentials or sensitive information.
* [ ] ensure email config is set correctly in application.yaml and mails are sent successfully.
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