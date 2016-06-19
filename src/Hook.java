
public class Hook {
	protected final String className;
	protected final String methodName;
	protected final String signature;

	public Hook(String className, String methodName, String signature)
	{
		this.className = className;
		this.methodName = methodName;
		this.signature = signature;
	}
}
