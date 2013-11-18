#!/bin/bash

ZANATA_SERVER=https://translate.zanata.org/zanata/
REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
USERNAME=admin
TOKEN=b6d7044e9ee3b2447c28fb7c50d86d98
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
