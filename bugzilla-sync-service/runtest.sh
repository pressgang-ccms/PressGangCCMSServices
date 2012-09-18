#!/bin/bash

REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
BUGZILLA_URL=bugzilla.redhat.com
BUGZILLA_USERNAME=pressgang-ccms-dev@redhat.com
BUGZILLA_PASSWORD=password
MAINCLASS=org.jboss.pressgang.ccms.services.bugzillasync.Main

# Get the directory hosting the script. This is important if the script is called from 
# another working directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

cd ${DIR}

java -DtopicIndex.bugzillaUrl=${BUGZILLA_URL} \
-DtopicIndex.bugzillaUsername=${BUGZILLA_USERNAME} \
-DtopicIndex.bugzillaPassword=${BUGZILLA_PASSWORD} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-cp target/classes:target/lib/* ${MAINCLASS}
