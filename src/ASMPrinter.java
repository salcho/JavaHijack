import org.objectweb.asm.*;
import java.io.FileInputStream;
import java.io.InputStream;

// Example class of the visitor pattern applied to ASM.
public class ASMPrinter {
	public static void main(String[] args) throws Exception
	{
		ClassVisitor visitor = new ClassVisitor(Opcodes.ASM4) {
			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces)
			{
				System.err.println(String.format("Visiting class: %s(%s) implements ", name, signature));
				super.visit(version, access, name, signature, superName, interfaces);
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions)
			{
				System.err.println(String.format("Visiting method: %s(%s)", name, signature));
				return super.visitMethod(access, name, desc, signature, exceptions);
			}



			@Override
			public void visitEnd()
			{
				super.visitEnd();
			}
		};


		InputStream fis = new FileInputStream("C:\\Users\\user\\Desktop\\TestClass.class");
//		InputStream classStream = ASMPrinter.class.getResourceAsStream("/java/lang/String.class");
		ClassReader classReader = new ClassReader(fis);
		classReader.accept(visitor, 0);
	}
}
