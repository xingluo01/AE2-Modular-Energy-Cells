@rem Dev client with JEI (the default viewer; see the -Pemi branch in build.gradle).
@rem --no-configuration-cache is required here: run* tasks fold the dev-run classpath
@rem (localRuntime / clientLocalRuntime) into the configuration cache, and a stale hit
@rem launches the game with the old classpath (symptom: a mod you just added never loads).
@rem %~dp0 is this script's own folder: when invoked by path from elsewhere the cwd is not
@rem the repo root, and a bare `gradlew` would not be found.
@call "%~dp0gradlew" runClient --no-configuration-cache
