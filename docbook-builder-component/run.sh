#!/bin/bash

STOMP_SERVER=localhost
REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest

#STOMP_SERVER=skynet.cloud.lab.eng.bne.redhat.com
#REST_SERVER=http://skynet.cloud.lab.eng.bne.redhat.com:8080/TopicIndex/seam/resource/rest
PORT=61613
USER=guest
PASS=guest
QUEUE=jms.queue.SkynetDocbookBuildQueue
SMTP=smtp.corp.redhat.com
VERBATIM_XML_ELEMENTS=screen,programlisting,literallayout,synopsis,address
INLINE_XML_ELEMENTS=code,prompt,command,firstterm,ulink,guilabel,filename,replaceable,parameter,literal,classname,sgmltag,guibutton,guimenuitem,guimenu,menuchoice,citetitle,systemitem,application,acronym,keycap,emphasis,package,quote,trademark,abbrev,phrase,anchor,citation,glossterm,link,xref,markup,tag,keycode,keycombo,accel,guisubmenu,keysym,shortcut,mousebutton,constant,errorcode,errorname,errortype,function,msgtext,property,returnvalue,symbol,token,varname,database,email,hardware,option,optional,type,methodname,interfacename,uri,productname,productversion
CONTENTS_INLINE_XML_ELEMENTS=title,term
NUMBER_OF_THREADS=1
MAINCLASS=com.redhat.topicindex.component.docbookrenderer.Main

# Get the directory hosting the script. This is important if the script is called from 
# another working directory
DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

cd ${DIR}

java -Xmx1024m \
-Dmail.smtp.host=${SMTP} \
-DtopicIndex.stompMessageServer=${STOMP_SERVER} \
-DtopicIndex.stompMessageServerPort=${PORT} \
-DtopicIndex.stompMessageServerUser=${USER} \
-DtopicIndex.stompMessageServerPass=${PASS} \
-DtopicIndex.stompMessageServerQueue=${QUEUE} \
-DtopicIndex.skynetServer=${REST_SERVER} \
-DtopicIndex.verbatimXMLElements=${VERBATIM_XML_ELEMENTS} \
-DtopicIndex.inlineXMLElements=${INLINE_XML_ELEMENTS} \
-DtopicIndex.contentsInlineXMLElements=${CONTENTS_INLINE_XML_ELEMENTS} \
-DNumberOfWorkerThreads=${NUMBER_OF_THREADS} \
-cp target/classes:target/lib/* ${MAINCLASS}
