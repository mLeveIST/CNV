#!/bin/bash

# Rollback .class files to original state
rm -r pt/
cp -r ../cnv/backup/pt/ .

# Update .java files
cp -r ../cnv/wip/myBIT/*.java ./BIT/myBIT/
cp ../cnv/wip/*.java pt/ulisboa/tecnico/cnv/server/

# Compile project
javac -cp . BIT/myBIT/*.java
javac -cp . pt/ulisboa/tecnico/cnv/server/*.java

# Re-instrument code
java -XX:-UseSplitVerifier BIT.myBIT.BITTool $1 pt/ulisboa/tecnico/cnv/solver/ pt/ulisboa/tecnico/cnv/solver/

# Backup instrumented code
cp -r pt/ulisboa/tecnico/cnv/solver/ ../cnv/instrumented/

# Copy project state to outside vm
cp -r . ../cnv/forgit/

# Run instrumented server
java -XX:-UseSplitVerifier pt.ulisboa.tecnico.cnv.server.WebServer -address "0.0.0.0" -port 8000
