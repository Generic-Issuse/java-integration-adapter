include ../Makefile.def

envl = LANG=fr_CA.iso88591 NLS_LANG=FRENCH_CANADA.WE8ISO8859P1

CLASS = .:../javaadapter/javaadapter.jar:../dbsyncplugin/dbsyncplugin.jar:${JAR_DIR}/scheduling.jar:${JAR_DIR}/json.jar:${JAR_DIR}/wljmsclient.jar:${JAR_DIR}/webserviceclient.jar:${JAR_DIR}/ojdbc14.jar:$(JAR_DIR)/am-constants-93.jar:$(JAR_DIR)/am-jni-93.jar:${JAR_DIR}/commons-logging.jar

JAR =	assetmanagerdbsync.jar
DIR =	classes

FILES =	assetmanagerdbsync.java

all:	${JAR}

$(JAR): $(FILES) ../javaadapter/javaadapter.jar ../dbsyncplugin/dbsyncplugin.jar
	$(JAVA_HOME)/bin/javac -d ${DIR} ${JAVAC_ARGS} -classpath ${CLASS} ${FILES}
	(cd ${DIR}; $(JAVA_HOME)/bin/jar cvf ../${JAR} *.class)

clean::
	rm -f ${JAR} ${DIR}/*.class hs_err_pid*.log

run::
	$(JAVA_HOME)/bin/java ${JAVA_ARGS} -classpath ${JAR}:${CLASS} javaadapter

test::
	$(JAVA_HOME)/bin/java ${JAVA_ARGS} -classpath ${JAR}:${CLASS} assetmanagerdbsync

debug::
	jswat

