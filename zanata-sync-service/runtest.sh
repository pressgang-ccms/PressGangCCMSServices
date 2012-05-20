#!/bin/bash

ZANATA_SERVER=http://zanatatest.usersys.redhat.com/
REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
USERNAME=admin
TOKEN=b6d7044e9ee3b2447c28fb7c50d86d98
PROJECT=skynet
PROJECT_VERSION=1
MAINCLASS=Main

java -DtopicIndex.zanataServer=${ZANATA_SERVER} \
-DtopicIndex.zanataUsername=${USERNAME} \
-DtopicIndex.zanataToken=${TOKEN} \
-DtopicIndex.zanataProject=${PROJECT} \
-DtopicIndex.zanataProjectVersion=${PROJECT_VERSION} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-jar target/zanata-sync-service-0.0.1-SNAPSHOT-jar-with-dependencies.jar
