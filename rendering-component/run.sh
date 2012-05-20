#!/bin/bash

STOMP_SERVER=localhost
REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
PORT=61613
USER=guest
PASS=guest
QUEUE=jms.queue.SkynetTopicRenderQueue
MAINCLASS=com.redhat.topicindex.component.topicrenderer.Main

java -DtopicIndex.stompMessageServer=${STOMP_SERVER} \
-DtopicIndex.stompMessageServerPort=${PORT} \
-DtopicIndex.stompMessageServerUser=${USER} \
-DtopicIndex.stompMessageServerPass=${PASS} \
-DtopicIndex.stompMessageServerQueue=${QUEUE} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-cp target/classes:target/lib/* ${MAINCLASS}
