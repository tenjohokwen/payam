## Exceptions thrown during DaoAuthProvider.authorize
* When retrieveUser is called:

    * InternalAuthenticationServiceException [This is when "loadedUser == null" or some unknown exception is thrown]
    * UsernameNotFoundException [when username is not found]

* When user details are checked

    * LockedException [when account has been locked]
    * DisabledException [When account is disabled]
    * AccountExpiredException [when account has expired]
    * CredentialsExpiredException [When credentials have expired]