#!/bin/bash

ZANATA_SERVER=http://zanatatest.usersys.redhat.com/zanata/
#ZANATA_SERVER=http://zanata-master-nukes.lab.eng.bne.redhat.com:8080/
REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
USERNAME=admin
#TOKEN=b6d7044e9ee3b2447c28fb7c50d86d98
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

java -DtopicIndex.zanataServer=${ZANATA_SERVER} \
-DtopicIndex.zanataUsername=${USERNAME} \
-DtopicIndex.zanataToken=${TOKEN} \
-DtopicIndex.zanataProject=${PROJECT} \
-DtopicIndex.zanataProjectVersion=${PROJECT_VERSION} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-DtopicIndex.defaultLocale=${DEFAULT_LOCALE} \
-DtopicIndex.minZanataCallInterval=${MIN_ZANATA_CALL_INTERVAL} \
-cp target/classes:target/lib/* ${MAINCLASS}
