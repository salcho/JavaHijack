import com.sun.org.apache.bcel.internal.Constants;
import com.sun.org.apache.bcel.internal.classfile.*;
import com.sun.org.apache.bcel.internal.generic.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class BCELModifier {

	private static String testPath = "C:\\vcs\\TestApp\\out\\production\\InstrumentationTestApp";
	private static String outPackage = "testpackage/";
	private final int HOOK_STATIC_CALL = 0;
	private final int HOOK_VIRTUAL_CALL = 1;
	private final int HOOK_VIRTUAL_FIELD = 2;
	private final int HOOK_STATIC_FIELD = 3;

	public void processClass(File classFile) throws Exception
	{
		JavaClass klass = loadClassFile(classFile);
		if (klass == null) return;

		Hook hook = new Hook("Intercept", "intBandit", "(Ljava/lang/String;)I");
		ConstantTableEntry targetReference = new ConstantTableEntry("java/lang/Integer", "parseInt");
		JavaClass newClass = hookStaticCall(klass, hook, targetReference);

		hook = new Hook("Intercept", "virtualBandit", "(Ljava/lang/StringBuilder;Ljava/lang/String;)Ljava/lang/StringBuilder;");
		targetReference = new ConstantTableEntry("java/lang/StringBuilder", "append");
		newClass = hookVirtualCall(newClass, hook, targetReference);

		hook = new Hook("Intercept", "ctorBandit", "(Ljava/lang/String;)Ljava/lang/String;");
		newClass = hookNewObject(newClass, hook, "java/lang/String");

		hook = new Hook("Intercept", "putFieldBandit", "(Ljavax/swing/text/html/parser/AttributeList;Ljava/lang/String;)V");
		targetReference = new ConstantTableEntry("javax/swing/text/html/parser/AttributeList", "name");
		newClass = hookVirtualAssignment(newClass, hook, targetReference);

		hook = new Hook("Intercept", "putStaticBandit", "(Ljava/lang/String;)V");
		targetReference = new ConstantTableEntry("com/sun/org/apache/xerces/internal/impl/Version", "fVersion");
		newClass = hookStaticAssignment(newClass, hook, targetReference);

		// Test Exceptions
		hook = new Hook("Intercept", "socketBandit", "(Ljava/lang/String;I)Ljava/net/Socket;");
		newClass = hookNewObject(newClass, hook, "java/net/Socket");

		// Dump modified instructions to class file
		dumpClass(classFile, newClass);

		System.out.println("\n");
	}

	private JavaClass loadClassFile(File classFile) throws IOException
	{
		ClassParser classParser = new ClassParser(new FileInputStream(classFile.getCanonicalFile()), classFile.getName());
		JavaClass klass = classParser.parse();

		if (klass == null)
		{
			System.err.println("Can't find class!");
			return null;
		}

		printOriginalClassInfo(klass);
		return klass;
	}

	private JavaClass hookStaticCall(JavaClass klass, Hook hook, ConstantTableEntry constantTableEntry)
	{
		int injectedIndex = injectMethodRef(klass, hook);

		int targetIndex = findTarget(klass.getConstantPool(), constantTableEntry.className, constantTableEntry.typeName, Constants.CONSTANT_Methodref);
		return hookSingleInstruction(klass, targetIndex, injectedIndex, HOOK_STATIC_CALL);
	}

	private JavaClass hookVirtualCall(JavaClass klass, Hook hook, ConstantTableEntry constantTableEntry)
	{
		int injectedIndex = injectMethodRef(klass, hook);

		int targetIndex = findTarget(klass.getConstantPool(), constantTableEntry.className, constantTableEntry.typeName, Constants.CONSTANT_Methodref);
		return hookSingleInstruction(klass, targetIndex, injectedIndex, HOOK_VIRTUAL_CALL);
	}

	private JavaClass hookNewObject(JavaClass klass, Hook hook, String className)
	{
		ClassGen classGen = new ClassGen(klass);

		ConstantPoolGen constantPoolGen = new ConstantPoolGen(klass.getConstantPool());
		int injectedIndex = constantPoolGen.addMethodref(hook.className, hook.methodName, hook.signature);
		klass.setConstantPool(constantPoolGen.getFinalConstantPool());

		int targetIndex = findTarget(klass.getConstantPool(), className, null, Constants.CONSTANT_Class);
		Method[] methods = locateNewObjects(klass, constantPoolGen, injectedIndex, targetIndex);

		classGen.setMethods(methods);
		classGen.setConstantPool(constantPoolGen);
		return classGen.getJavaClass();
	}

	private Method[] locateNewObjects(JavaClass klass, ConstantPoolGen constantPoolGen, int injectedIndex, int targetIndex)
	{
		Method[] methods = new Method[klass.getMethods().length];
		for (int i = 0; i < klass.getMethods().length; i++)
		{
			MethodGen methodGen = new MethodGen(klass.getMethods()[i], klass.getClassName(), constantPoolGen);
			InstructionList instructionList = methodGen.getInstructionList();
			InstructionHandle[] instructionHandles = instructionList.getInstructionHandles();

			Instruction[] methodInstructions = instructionList.getInstructions();
			patchMethod(injectedIndex, targetIndex, instructionHandles, methodInstructions);
			methods[i] = methodGen.getMethod();
		}
		return methods;
	}

	private void patchMethod(int injectedIndex, int targetIndex, InstructionHandle[] instructionHandles, Instruction[] methodInstructions)
	{
		for (int j = 0; j < methodInstructions.length; j++)
		{
			Instruction currentInstruction = methodInstructions[j];

			if (currentInstruction.getName().equals("new") && ((NEW) currentInstruction).getIndex() == targetIndex)
			{
				instructionHandles[j].setInstruction(new NOP());
				for (int k = j + 1; k < methodInstructions.length; k++)
				{
					String instruction = methodInstructions[k].getName();
					// A new should always be followed by a dup!
					if (instruction.equals("dup"))
					{
						instructionHandles[k].setInstruction(new NOP());
						continue;
					}

					if (instruction.equals("invokespecial"))
					{
						instructionHandles[k].setInstruction(new INVOKESTATIC(injectedIndex));
						break;
					}
				}
			}
		}
	}

	private JavaClass hookVirtualAssignment(JavaClass klass, Hook hook, ConstantTableEntry constantTableEntry)
	{
		int injectedIndex = injectMethodRef(klass, hook);

		int targetIndex = findTarget(klass.getConstantPool(), constantTableEntry.className, constantTableEntry.typeName, Constants.CONSTANT_Fieldref);
		return hookSingleInstruction(klass, targetIndex, injectedIndex, HOOK_VIRTUAL_FIELD);
	}

	private JavaClass hookStaticAssignment(JavaClass klass, Hook hook, ConstantTableEntry constantTableEntry)
	{
		int injectedIndex = injectMethodRef(klass, hook);

		int targetIndex = findTarget(klass.getConstantPool(), constantTableEntry.className, constantTableEntry.typeName, Constants.CONSTANT_Fieldref);
		return hookSingleInstruction(klass, targetIndex, injectedIndex, HOOK_STATIC_FIELD);
	}

	private JavaClass hookSingleInstruction(JavaClass klass, int findEntry, int replaceWith, int hookType)
	{
		ClassGen classGen = new ClassGen(klass);
		ConstantPoolGen constantPoolGen = new ConstantPoolGen(klass.getConstantPool());
		Method[] methods = new Method[klass.getMethods().length];

		String lookFor;

		switch (hookType)
		{
			case HOOK_STATIC_CALL:
				lookFor = "invokestatic";
				break;
			case HOOK_VIRTUAL_CALL:
				lookFor = "invokevirtual";
				break;
			case HOOK_VIRTUAL_FIELD:
				lookFor = "putfield";
				break;
			case HOOK_STATIC_FIELD:
				lookFor = "putstatic";
				break;
			default:
				return null;
		}

		for (int i = 0; i < klass.getMethods().length; i++)
		{
			MethodGen methodGen = new MethodGen(klass.getMethods()[i], klass.getClassName(), constantPoolGen);
			InstructionList instructionList = methodGen.getInstructionList();
			InstructionHandle[] instructionHandles = instructionList.getInstructionHandles();

			for (int j = 0; j < instructionList.getInstructions().length; j++)
			{
				Instruction currentInstruction = instructionList.getInstructions()[j];

				if (currentInstruction.getName().equals(lookFor))
				{
					switch (hookType)
					{
						case HOOK_STATIC_CALL:
							if (((INVOKESTATIC) currentInstruction).getIndex() == findEntry)
							{
								instructionHandles[j].setInstruction(new INVOKESTATIC(replaceWith));
							}
							break;
						case HOOK_VIRTUAL_CALL:
							if (((INVOKEVIRTUAL) currentInstruction).getIndex() == findEntry)
							{
								instructionHandles[j].setInstruction(new INVOKESTATIC(replaceWith));
							}
							break;
						case HOOK_VIRTUAL_FIELD:
							if (((PUTFIELD) currentInstruction).getIndex() == findEntry)
							{
								instructionHandles[j].setInstruction(new INVOKESTATIC(replaceWith));
							}
							break;
						case HOOK_STATIC_FIELD:
							if (((PUTSTATIC) currentInstruction).getIndex() == findEntry)
							{
								instructionHandles[j].setInstruction(new INVOKESTATIC(replaceWith));
							}
							break;
					}
				}
			}
			methods[i] = methodGen.getMethod();
		}

		classGen.setMethods(methods);
		classGen.setConstantPool(constantPoolGen);
		return classGen.getJavaClass();
	}

	private void dumpClass(File classFile, JavaClass newClass)
	{
		try
		{
			newClass.dump(outPackage + classFile.getName());
		} catch (Exception e)
		{
			e.printStackTrace();
		}
	}

	private int injectMethodRef(JavaClass klass, Hook hook)
	{
		ConstantPoolGen poolGen = new ConstantPoolGen(klass.getConstantPool());
		int injectedIndex = poolGen.addMethodref(hook.className, hook.methodName, hook.signature);
		klass.setConstantPool(poolGen.getFinalConstantPool());
		return injectedIndex;
	}

	private int findTarget(ConstantPool constantPool, String className, String objName, byte type)
	{
		for (int i = 0; i < constantPool.getConstantPool().length; i++)
		{
			Constant constant = constantPool.getConstant(i);
			if (constant == null)
			{
				continue;
			}

			if (constant.getTag() == type)
			{
				boolean matches;
				switch (type)
				{
					case Constants.CONSTANT_Methodref:
						matches = resolveMethodRef(constantPool, i, className, objName);
						break;
					case Constants.CONSTANT_Fieldref:
						matches = resolveFieldRef(constantPool, i, className, objName);
						break;
					case Constants.CONSTANT_Class:
						matches = resolveClassRef(constantPool, i, className);
						break;
					default:
						continue;
				}
				if (matches)
				{
					return i;
				}
			}
		}
		return 0;
	}

	private boolean resolveClassRef(ConstantPool constantPool, int constantIndex, String className)
	{
		ConstantClass constant = (ConstantClass) constantPool.getConstant(constantIndex);
		ConstantUtf8 nameEntry = (ConstantUtf8) constantPool.getConstant(constant.getNameIndex());
		return nameEntry.getBytes().equals(className);
	}

	private boolean resolveFieldRef(ConstantPool constantPool, int constantIndex, String className, String methodName)
	{
		ConstantFieldref constant = (ConstantFieldref) constantPool.getConstant(constantIndex);
		int classIndex = constant.getClassIndex();
		int nameAndType = constant.getNameAndTypeIndex();

		ConstantNameAndType constantNameAndType = (ConstantNameAndType) constantPool.getConstant(nameAndType);
		return constantPool.getConstantString(classIndex, Constants.CONSTANT_Class).equals(className) && constantNameAndType.getName(constantPool).equals(methodName);
	}

	private boolean resolveMethodRef(ConstantPool constantPool, int constantIndex, String className, String methodName)
	{
		ConstantMethodref constant = (ConstantMethodref) constantPool.getConstant(constantIndex);
		int classIndex = constant.getClassIndex();
		int nameAndType = constant.getNameAndTypeIndex();

		ConstantNameAndType constantNameAndType = (ConstantNameAndType) constantPool.getConstant(nameAndType);
		return constantPool.getConstantString(classIndex, Constants.CONSTANT_Class).equals(className) && constantNameAndType.getName(constantPool).equals(methodName);
	}

	private void printOriginalClassInfo(JavaClass klass)
	{
		System.out.println(String.format("\t[*] %d entries in constants table", klass.getConstantPool().getLength()));
		System.out.println(String.format("\t[*] %d attributes",klass.getAttributes().length));
		System.out.println(String.format("\t[*] %d interfaces implemented", klass.getInterfaceNames().length));
		System.out.println(String.format("\t[*] %d fields",klass.getFields().length));

		Method[] methods = klass.getMethods();
		System.out.println(String.format("\t[*] %d methods", methods.length));
	}

	public static void main(String[] args) throws Exception
	{
		BCELModifier bcelModifier = new BCELModifier();

		File folder = new File(testPath);
		for (File file : folder.listFiles())
		{
			System.out.println("Processing " + file);
			if (file.getName().endsWith("class"))
			{
				bcelModifier.processClass(file);
			}
		}

		System.out.println("Finished!\n");
	}
}
