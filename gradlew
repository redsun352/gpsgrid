#!/bin/sh

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)

if [ -z "$JAVA_HOME" ] ; then
  if command -v java >/dev/null 2>&1 ; then
    JAVACMD=java
  else
    echo "ERROR: JAVA_HOME is not set and no 'java' command could be found." >&2
    exit 1
  fi
else
  JAVACMD="$JAVA_HOME/bin/java"
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
exec "$JAVACMD" -Dorg.gradle.appname=gradlew -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
