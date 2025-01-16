package guide.javanativeinterface;

public class NativeInterfaceExample {
    // Declare a native method
    public native String sayHello(String name);

    // Load the native library
    static {
        System.loadLibrary("nativeexample");
    }

    public static void main(String[] args) {
        NativeInterfaceExample example = new NativeInterfaceExample();
        String response = example.sayHello("World");
        System.out.println(response);
    }
}
