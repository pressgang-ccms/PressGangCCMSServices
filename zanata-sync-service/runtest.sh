#!/bin/bash

ZANATA_SERVER=http://zanatatest.usersys.redhat.com/zanata/
REST_SERVER=http://localhost:8080/pressgang-ccms/rest
USERNAME=admin
TOKEN=1fb75f14f8c0c791b628c9ff8cd2fd75
PROJECT=skynet-topics
PROJECT_VERSION=1
MAINCLASS=org.jboss.pressgang.ccms.services.zanatasync.Main
DEFAULT_LOCALE=en-US
MIN_ZANATA_CALL_INTERVAL=0.2

# Get the directory hosting the script. This is important if the script is called from 
# another working directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

cd ${DIR}

java -Dpressgang.zanataServer=${ZANATA_SERVER} \
-Dpressgang.zanataUsername=${USERNAME} \
-Dpressgang.zanataToken=${TOKEN} \
-Dpressgang.zanataProject=${PROJECT} \
-Dpressgang.zanataProjectVersion=${PROJECT_VERSION} \
-Dpressgang.restServer=${REST_SERVER} \
-Dpressgang.defaultLocale=${DEFAULT_LOCALE} \
-Dpressgang.minZanataCallInterval=${MIN_ZANATA_CALL_INTERVAL} \
-cp target/classes:target/lib/* ${MAINCLASS}
