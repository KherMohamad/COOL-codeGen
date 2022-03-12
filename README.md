# COOL-codeGen
Java program that generates equivalent low-level code for a programming language called COOL(Classroom Object Oriented Language).
The program uses a semantic analyzer that returns an AST(Abstract Syntax Tree) as a base. It uses that AST to generate corresponding MIPS code for a COOL program.
The AST traversal and code generation are done using a parsing TOOL called ANTLR. the Java template engine StringTemplate is also used to make formatting the output code easier
