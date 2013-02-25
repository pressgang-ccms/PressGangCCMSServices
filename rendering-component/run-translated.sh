#!/bin/sh

STOMP_SERVER=localhost
REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
PORT=61613
USER=guest
PASS=guest
QUEUE=jms.queue.PressGangCCMSTranslatedTopicRenderQueue
MAINCLASS=org.jboss.pressgang.ccms.services.docbookrenderer.Main

# Get the directory hosting the script. This is important if the script is called from 
# another working directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

cd ${DIR}

eval java -DtopicIndex.stompMessageServer=${STOMP_SERVER} \
-DtopicIndex.stompMessageServerPort=${PORT} \
-DtopicIndex.stompMessageServerUser=${USER} \
-DtopicIndex.stompMessageServerPass=${PASS} \
-DtopicIndex.stompMessageServerQueue=${QUEUE} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-cp target/classes:target/lib/* ${MAINCLASS} \
"$@" "&"

PID=$!
# Trap common signals and relay them to the service process
trap "kill -HUP  $PID" HUP
trap "kill -TERM $PID" INT
trap "kill -QUIT $PID" QUIT
trap "kill -PIPE $PID" PIPE
trap "kill -TERM $PID" TERM

# Wait until the process exits
WAIT_STATUS=128
while [ "$WAIT_STATUS" -ge 128 ] && [ "$WAIT_STATUS" != 255 ]; do
 wait $PID 2>/dev/null
 WAIT_STATUS=$?
done
if [ "$WAIT_STATUS" -lt 127 ] || [ "$WAIT_STATUS" == 255 ]; then
 STATUS=$WAIT_STATUS
else
 STATUS=0
fi

# Wait for a complete shutdown
wait $PID 2>/dev/null

exit $STATUS
