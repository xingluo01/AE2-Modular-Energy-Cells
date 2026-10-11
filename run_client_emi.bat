@rem Dev client with EMI instead of JEI (-Pemi switches the branch in build.gradle).
@rem The AE2 JEI bridge stays out on purpose: its mod metadata requires JEI.
@call "%~dp0gradlew" runClient -Pemi --no-configuration-cache
