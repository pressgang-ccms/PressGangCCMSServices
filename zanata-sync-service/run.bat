@ECHO OFF

REM SET ZANATA_SERVER=https://translate.engineering.redhat.com
REM SET REST_SERVER=http://skynet.cloud.lab.eng.bne.redhat.com:8080/TopicIndex/seam/resource/rest
SET ZANATA_SERVER=http://csprocessor.cloud.lab.eng.bne.redhat.com:8080/zanata
SET REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
SET USERNAME=admin
SET TOKEN=b6d7044e9ee3b2447c28fb7c50d86d98
SET MAINCLASS=Main
SET PROJECT=skynet
SET PROJECT_VERSION=1

java -DNumberOfWorkerThreads=3 -DtopicIndex.zanataServer=%ZANATA_SERVER% -DtopicIndex.zanataUsername=%USERNAME% -DtopicIndex.zanataToken=%TOKEN% -DtopicIndex.zanataProject=%PROJECT% -DtopicIndex.projectVersion=%PROJECT_VERSION% -DtopicIndex.skynetServer=%REST_SERVER% -cp bin;lib/* %MAINCLASS%