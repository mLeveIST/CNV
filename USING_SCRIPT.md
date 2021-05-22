# Using `run.sh <arg>`

Use to clean/compile/instrument/run EC2 workers server code

## Setting up folders

- Open zip and drop folder contents inside vm shared folder (cnv)

- `cnv-project-copy` has a copy of the cnv-project folder inside the vm
- `instrumented` has the instrumented .class files
- `original` has a backup of original project files
- `wip` is the working directory, where changes will be automatically applied to code after running the script

## Arguments

- Has 1 numeric argument, which is the instrumentation bit mask
- Tells the script what should be the metrics to retrive in instrumentation
- Each bit in the mask represents a metric to instrument
- In order from less significant bit: Instruction count, basic block count, method count, field loads, field stores, loads, stores, news, new arrays, new "a" arrays, new multi "a" arrays

Examples:
- Pass `0` to run server with no instrumentation
- Pass `1` to run server with instruction count instrumentation (mask is 00000000001)
- Pass `4` to run server with method count instrumentation (mask is 00000000100)
- Pass `5` to run server with instruction and method count instrumentation (mask is 00000000101)
- Pass `2047` to run full instrumentation (mask is 11111111111)

## Runing script

Inside vm, go to cnv-project dir

Place script inside project root:
- `cp ../cnv/run.sh .`

Add execution permissions:
- `chmod +x run.sh`

Run script:
- `./run.sh <arg>`
- `<arg>` is the instrumentation mask (see above)



