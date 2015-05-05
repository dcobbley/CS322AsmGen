.SUFFIXES:	.jj .java
.PHONY:		all clean

all:
	javac *.java

Parser.java: Parser.jj
	javacc Parser.jj

clean:
	-rm *.class ast/*.class compiler/*.class
