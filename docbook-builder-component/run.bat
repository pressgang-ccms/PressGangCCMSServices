@ECHO OFF

REM SET STOMP_SERVER=skynet.cloud.lab.eng.bne.redhat.com
REM SET REST_SERVER=http://skynet.cloud.lab.eng.bne.redhat.com:8080/TopicIndex/seam/resource/rest
SET STOMP_SERVER=localhost
SET REST_SERVER=http://localhost:8080/TopicIndex/seam/resource/rest
SET PORT=61613
SET USER=guest
SET PASS=guest
SET QUEUE=jms.queue.SkynetDocbookBuildQueue
SET SMTP=smtp.corp.redhat.com
SET MAINCLASS=com.redhat.topicindex.component.docbookrenderer.Main
SET VERBATIM_XML_ELEMENTS=screen,programlisting
SET INLINE_XML_ELEMENTS=code,prompt,command,firstterm,ulink,guilabel,filename,replaceable,parameter,literal,classname,sgmltag,guibutton,guimenuitem,guimenu,menuchoice,citetitle,systemitem,application
SET CONTENTS_INLINE_XML_ELEMENTS=title,term

java -Dmail.smtp.host=%SMTP% -DtopicIndex.stompMessageServer=%STOMP_SERVER% -DtopicIndex.stompMessageServerPort=%PORT% -DtopicIndex.stompMessageServerUser=%USER% -DtopicIndex.stompMessageServerPass=$%PASS% -DtopicIndex.stompMessageServerQueue=%QUEUE% -DtopicIndex.skynetServer=%REST_SERVER% -DtopicIndex.verbatimXMLElements=%VERBATIM_XML_ELEMENTS% -DtopicIndex.inlineXMLElements=%INLINE_XML_ELEMENTS% -DtopicIndex.contentsInlineXMLElements=%CONTENTS_INLINE_XML_ELEMENTS% -jar target/docbook-builder-0.0.1-SNAPSHOT-jar-with-dependencies.jar