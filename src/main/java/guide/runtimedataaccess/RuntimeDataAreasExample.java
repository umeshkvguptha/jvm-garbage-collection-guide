package guide.runtimedataaccess;

public class RuntimeDataAreasExample {
    // Static variable (Method Area)
    static int staticVar = 42;

    public static void main(String[] args) {
        // Local variable (Stack)
        int localVar = 10;

        // Object (Heap)
        Person person = new Person("Alice", 30);

        // Method invocation (Stack Frame)
        displayPersonInfo(person);
    }

    public static void displayPersonInfo(Person person) {
        // Accessing object fields (Heap)
        System.out.println("Name: " + person.name + ", Age: " + person.age);
    }
}

class Person {
    // Instance variables (Heap)
    String name;
    int age;

    // Constructor
    public Person(String name, int age) {
        this.name = name;
        this.age = age;
    }
}