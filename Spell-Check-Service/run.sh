#!/bin/bash

# For some reason using the Maven shade or assembly plug-ins results in 
# an infinite loop, and a stack overflow error. So the maven-dependency-plugin
# is used to collate the JAR files required for this application to run.
# In addition, the Hunspell library requires access to the dictionaries as 
# files, which means we can't have a self contained JAR file anyway.

# To deploy this application run mvn compile and mvn package, 
# copy the target/classes and target/lib files to the destination directory, 
# and run this script file. 

#The directory structure should look like:
#\
#  run.sh
#  target
#    lib
#      JAR files copied by the mvn package command
#    classes
#      The various CLASS files created by mvn
#      customdict
#        The custom dictionary files

#STOMP_SERVER=localhost
#REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest

STOMP_SERVER=skynet.usersys.redhat.com
REST_SERVER=http://skynet.usersys.redhat.com:8080/TopicIndex/seam/resource/rest
PORT=61613
USER=guest
PASS=guest
QUEUE=none
MAINCLASS=com.redhat.topicindex.syntaxchecker.Main
QUERY="query;topicEditedInLastDays=2"
#QUERY="query;tag19=1;tag119=1;catint5=And;tag133=1;tag132=1;tag14=0"

java \
-DtopicIndex.stompMessageServer=${STOMP_SERVER} \
-DtopicIndex.stompMessageServerPort=${PORT} \
-DtopicIndex.stompMessageServerUser=${USER} \
-DtopicIndex.stompMessageServerPass=${PASS} \
-DtopicIndex.stompMessageServerQueue=${QUEUE} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-DtopicIndex.spellCheckQuery=${QUERY} \
-cp ./target/classes:./target/lib/* ${MAINCLASS}
