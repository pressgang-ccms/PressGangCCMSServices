@ECHO OFF

REM SET STOMP_SERVER=skynet.cloud.lab.eng.bne.redhat.com
REM SET REST_SERVER=http://skynet.cloud.lab.eng.bne.redhat.com:8080/TopicIndex/seam/resource/rest
SET STOMP_SERVER=localhost
SET REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
SET PORT=61613
SET USER=guest
SET PASS=guest
SET QUEUE=jms.queue.SkynetTopicRenderQueue
SET MAINCLASS=com.redhat.topicindex.component.topicrenderer.Main

java -DNumberOfWorkerThreads=1 -DcIndex.stompMessageServer=%STOMP_SERVER% -DtopicIndex.stompMessageServerPort=%PORT% -DtopicIndex.stompMessageServerUser=%USER% -DtopicIndex.stompMessageServerPass=$%PASS% -DtopicIndex.stompMessageServerQueue=%QUEUE% -DtopicIndex.skynetServer=%REST_SERVER% -jar target/renderer-0.0.1-SNAPSHOT-jar-with-dependencies.jar