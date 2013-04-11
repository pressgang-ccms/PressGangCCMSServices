#!/bin/bash

ZANATA_SERVER=https://translate.engineering.redhat.com/
REST_SERVER=http://skynet.usersys.redhat.com:8080/TopicIndex/seam/resource/rest
USERNAME=lnewson
TOKEN=02c45e26c4bcd4bb1b756e5edf94395d
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
