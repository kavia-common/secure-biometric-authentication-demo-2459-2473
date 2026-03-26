#!/bin/bash
cd /home/kavia/workspace/code-generation/secure-biometric-authentication-demo-2459-2473/android_app_frontend
./gradlew lint
LINT_EXIT_CODE=$?
if [ $LINT_EXIT_CODE -ne 0 ]; then
   exit 1
fi

