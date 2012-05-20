#!/bin/bash

REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
BUGZILLA_URL=bugzilla.redhat.com
BUGZILLA_USERNAME=mcaspers@redhat.com
BUGZILLA_PASSWORD=password
MAINCLASS=Main

java -DtopicIndex.bugzillaUrl=${BUGZILLA_URL} \
-DtopicIndex.bugzillaUsername=${BUGZILLA_USERNAME} \
-DtopicIndex.bugzillaPassword=${BUGZILLA_PASSWORD} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-cp target/classes:target/lib/* ${MAINCLASS}