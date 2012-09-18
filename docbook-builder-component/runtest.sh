#!/bin/bash

STOMP_SERVER=localhost
REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest

#STOMP_SERVER=skynet.usersys.redhat.com
#REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
PORT=61613
USER=guest
PASS=guest
QUEUE=jms.queue.SkynetDocbookBuildQueue
SMTP=smtp.corp.redhat.com
NUMBER_OF_THREADS=1
MAINCLASS=org.jboss.pressgang.ccms.services.docbookbuilder.Main

# Get the directory hosting the script. This is important if the script is called from 
# another working directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

cd ${DIR}

echo "java -Xmx1024m \
-Dmail.smtp.host=${SMTP} \
-DtopicIndex.stompMessageServer=${STOMP_SERVER} \
-DtopicIndex.stompMessageServerPort=${PORT} \
-DtopicIndex.stompMessageServerUser=${USER} \
-DtopicIndex.stompMessageServerPass=${PASS} \
-DtopicIndex.stompMessageServerQueue=${QUEUE} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-DNumberOfWorkerThreads=${NUMBER_OF_THREADS} \
-cp target/classes:target/lib/* ${MAINCLASS}"

java -Xmx1024m \
-Dmail.smtp.host=${SMTP} \
-DtopicIndex.stompMessageServer=${STOMP_SERVER} \
-DtopicIndex.stompMessageServerPort=${PORT} \
-DtopicIndex.stompMessageServerUser=${USER} \
-DtopicIndex.stompMessageServerPass=${PASS} \
-DtopicIndex.stompMessageServerQueue=${QUEUE} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-DNumberOfWorkerThreads=${NUMBER_OF_THREADS} \
-cp target/classes:target/lib/* ${MAINCLASS}
