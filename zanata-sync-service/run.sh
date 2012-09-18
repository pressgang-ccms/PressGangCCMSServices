#!/bin/bash

ZANATA_SERVER=https://translate.zanata.org/zanata/
REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
USERNAME=admin
TOKEN=b6d7044e9ee3b2447c28fb7c50d86d98
PROJECT=skynet-topics
PROJECT_VERSION=1
MAINCLASS=org.jboss.pressgang.ccms.services.zanatasync.Main
NUMBER_LOCALES=25
# 1 hour in milliseconds
TOTAL_SYNC_TIME=3600000
DEFAULT_LOCALE=en-US

# Get the directory hosting the script. This is important if the script is called from 
# another working directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

cd ${DIR}

java -DtopicIndex.zanataServer=${ZANATA_SERVER} \
-DtopicIndex.zanataUsername=${USERNAME} \
-DtopicIndex.zanataToken=${TOKEN} \
-DtopicIndex.zanataProject=${PROJECT} \
-DtopicIndex.zanataProjectVersion=${PROJECT_VERSION} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-DtopicIndex.numberOfZanataLocales=${NUMBER_LOCALES} \
-DtopicIndex.zanataSyncTime=${TOTAL_SYNC_TIME} \
-DtopicIndex.defaultLocale=${DEFAULT_LOCALE} \
-cp target/classes:target/lib/* ${MAINCLASS}
