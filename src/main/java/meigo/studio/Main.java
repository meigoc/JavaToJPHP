package meigo.studio;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class Main {
    private static final String ANSI_GREEN  = "\u001B[32m";
    private static final String ANSI_RED    = "\u001B[31m";
    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RESET  = "\u001B[0m";

    private static class MethodInfo {
        String typeLabel;
        String className;
        String signature;
        boolean isStatic;
        MethodInfo(String t, String c, String s, boolean st) {
            this.typeLabel = t;
            this.className = c;
            this.signature = s;
            this.isStatic  = st;
        }
    }

    public static void main(String[] args) throws IOException {
        // 1) Сбор JAR-файлов из classpath
        String classpath = System.getProperty("java.class.path");
        String sep       = System.getProperty("path.separator");
        String fs        = System.getProperty("file.separator");

        List<String> jars = Pattern.compile(Pattern.quote(sep))
                .splitAsStream(classpath)
                .filter(p -> p.toLowerCase().endsWith(".jar"))
                .filter(p -> !p.toLowerCase().contains("jre" + fs + "lib" + fs))
                .filter(p -> !p.toLowerCase().endsWith("idea_rt.jar"))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .collect(Collectors.toList());

        if (jars.size() > 1) {
            System.out.println("На данный момент, поддерживается только 1 библиотека");
            System.exit(1);
        }
        if (jars.isEmpty()) {
            System.out.println("Не найдено ни одной библиотеки для анализа");
            System.exit(1);
        }

        String jarPath = jars.get(0);
        System.out.println("Работаем с библиотекой: " + jarPath);

        // 2) Извлечение имён классов
        List<String> classNames = new ArrayList<>();
        try (JarFile jar = new JarFile(jarPath)) {
            jar.stream()
                    .filter(e -> !e.isDirectory() && e.getName().endsWith(".class"))
                    .forEach(e -> {
                        String name = e.getName()
                                .replace('/', '.')
                                .replace('\\', '.')
                                .replaceAll("\\.class$", "");
                        if (!name.equals("module-info")) {
                            classNames.add(name);
                        }
                    });
        }
        Collections.sort(classNames);

        // 3) Загрузка и сбор SUMMARY + статистика
        URL jarUrl = new File(jarPath).toURI().toURL();
        List<MethodInfo> summary = new ArrayList<>();
        int totalMethods = 0;
        int compatibleMethods = 0;
        Set<String> classesWithCompatible = new LinkedHashSet<>();

        try (URLClassLoader loader = new URLClassLoader(new URL[]{jarUrl}, Main.class.getClassLoader())) {
            for (String clsName : classNames) {
                // пропускаем внутренние классы
                if (clsName.contains("$")) {
                    System.out.printf("Skipping inner class: %s%n", clsName);
                    continue;
                }
                System.out.println(clsName);
                System.out.println("Methods:");
                try {
                    Class<?> cls = Class.forName(clsName, false, loader);
                    for (Method m : cls.getDeclaredMethods()) {
                        totalMethods++;
                        if (!Modifier.isPublic(m.getModifiers())) {
                            continue;
                        }
                        boolean isStatic = Modifier.isStatic(m.getModifiers());

                        // Определяем возвращаемый тип
                        Class<?> rt = m.getReturnType();
                        String typeLabel;
                        if (rt.equals(void.class)) {
                            typeLabel = "VOID";
                        } else if (rt.equals(String.class)) {
                            typeLabel = "STRING";
                        } else if (rt.equals(int.class) || rt.equals(Integer.class)) {
                            typeLabel = "INT";
                        } else if (rt.equals(long.class) || rt.equals(Long.class)) {
                            typeLabel = "LONG";
                        } else if (rt.equals(boolean.class) || rt.equals(Boolean.class)) {
                            typeLabel = "BOOL";
                        } else if (rt.getSimpleName().equals("Memory")) {
                            typeLabel = "ANY";
                        } else if (rt.getSimpleName().equals("ArrayMemory")
                                || rt.isArray()
                                || List.class.isAssignableFrom(rt)) {
                            typeLabel = "LIST";
                        } else {
                            continue; // не поддерживаемый тип
                        }

                        // Обрабатываем параметры, пропуская Environment
                        Class<?>[] pts = m.getParameterTypes();
                        List<String> paramTypes = new ArrayList<>();
                        for (Class<?> p : pts) {
                            String pn = p.getSimpleName();
                            if (pn.equals("Environment")) {
                                continue;
                            }
                            if (pn.equals("Memory")) {
                                paramTypes.add("any");
                            } else if (pn.equals("ArrayMemory")
                                    || p.isArray()
                                    || List.class.isAssignableFrom(p)) {
                                paramTypes.add("array");
                            } else {
                                String simple = pn.toLowerCase();
                                switch (simple) {
                                    case "string":
                                        paramTypes.add("string");
                                        break;
                                    case "int":
                                        paramTypes.add("int");
                                        break;
                                    case "long":
                                        paramTypes.add("long");
                                        break;
                                    case "boolean":
                                        paramTypes.add("bool");
                                        break;
                                    default:
                                        paramTypes.add("any");
                                        break;
                                }
                            }
                        }

                        // Формируем сигнатуру
                        String sig = m.getName() + "(" + String.join(", ", paramTypes) + ")";

                        // Сохраняем в SUMMARY и печатаем
                        summary.add(new MethodInfo(typeLabel, clsName, sig, isStatic));
                        compatibleMethods++;
                        classesWithCompatible.add(clsName);

                        String staticMarker = isStatic
                                ? " " + ANSI_YELLOW + "[STATIC]" + ANSI_RESET
                                : "";
                        System.out.printf("  %sCOMPATIBLE %s.%s %s%s%n",
                                ANSI_GREEN, clsName, sig, typeLabel, staticMarker);
                    }
                } catch (Throwable t) {
                    System.out.println("  (не удалось загрузить класс)");
                }
                System.out.println();
            }
        }

        int totalFiles = classNames.size();
        int convertedFiles = classesWithCompatible.size();

        // 4) Печать SUMMARY
        System.out.println("SUMMARY:");
        for (MethodInfo info : summary) {
            String staticMarker = info.isStatic
                    ? " " + ANSI_YELLOW + "[STATIC]" + ANSI_RESET
                    : "";
            System.out.printf("%s %s.%s%s%n",
                    info.typeLabel, info.className, info.signature, staticMarker);
        }

        // 5) Генерация PHP обёрток
        generatePhpWrappers(summary);

        // 6) Статистика
        int compatiblePercent = totalMethods > 0
                ? compatibleMethods * 100 / totalMethods
                : 0;
        int convertedPercent = totalFiles > 0
                ? convertedFiles * 100 / totalFiles
                : 0;

        System.out.println();
        System.out.println("COMPATIBLE METHODS: " + compatibleMethods + " (" + compatiblePercent + "%)");
        System.out.println("CONVERTED FILES: " + convertedFiles + " (" + convertedPercent + "%)");
        System.out.println("TOTAL FILES: " + totalFiles);
        System.out.println("TOTAL METHODS: " + totalMethods);

        // 7) Подготовка Java-оберток
        PrepareJavaWrappers(jarPath, summary);

    }

    /**
     * Генерирует для каждого класса из summary PHP-файл в папке sdk/...
     */
    private static void generatePhpWrappers(List<MethodInfo> summary) throws IOException {
        // Группируем по классу
        Map<String, List<MethodInfo>> byClass = new LinkedHashMap<>();
        for (MethodInfo mi : summary) {
            if (!byClass.containsKey(mi.className)) {
                byClass.put(mi.className, new ArrayList<MethodInfo>());
            }
            byClass.get(mi.className).add(mi);
        }

        File sdkRoot = new File("sdk");
        if (!sdkRoot.exists()) {
            sdkRoot.mkdir();
        }

        for (Map.Entry<String, List<MethodInfo>> kv : byClass.entrySet()) {
            String fullClass = kv.getKey(); // e.g. com.example.Outer or com.example.Outer$Inner
            List<MethodInfo> methods = kv.getValue();

            // Определяем пути и имена для PHP
            String phpNamespace;
            String phpClassName;
            File phpFile;
            if (fullClass.contains("$")) {
                // inner
                String outer = fullClass.substring(0, fullClass.indexOf('$'));
                String inner = fullClass.substring(fullClass.indexOf('$') + 1);
                String[] pkg = outer.split("\\.");
                String pkgPath = String.join(File.separator, pkg);
                File outerDir = new File(sdkRoot, pkgPath + File.separator + pkg[pkg.length - 1]);
                outerDir.mkdirs();
                phpFile = new File(outerDir, inner + ".php");

                phpNamespace = outer.replace('.', '\\');
                phpClassName = inner;
            } else {
                // normal
                String[] parts = fullClass.split("\\.");
                phpClassName = parts[parts.length - 1];
                String[] pkg = Arrays.copyOf(parts, parts.length - 1);
                String pkgPath = String.join(File.separator, pkg);
                File dir = new File(sdkRoot, pkgPath);
                dir.mkdirs();
                phpFile = new File(dir, phpClassName + ".php");

                phpNamespace = String.join("\\", pkg);
            }

            try (BufferedWriter w = new BufferedWriter(new FileWriter(phpFile))) {
                w.write("<?php\n");
                w.write("namespace " + phpNamespace + ";\n\n");
                w.write("/**\n");
                w.write(" * This class was automatically created using JavaToJPHP (github.com/meigoc)\n");
                w.write(" * The original Java class: " + fullClass + "\n");
                w.write(" */\n\n");
                w.write("/**\n");
                w.write(" * Class " + phpClassName + "\n");
                w.write(" */\n");
                w.write("class " + phpClassName + "\n{\n");

                for (MethodInfo mi : methods) {
                    // PHP-докблок
                    w.write("    /**\n");
                    w.write("     * JavaToJPHP Generated Bundle\n");
                    // @param
                    String argsInside = mi.signature.substring(
                            mi.signature.indexOf('(') + 1,
                            mi.signature.indexOf(')')
                    );
                    String[] args = argsInside.isEmpty()
                            ? new String[0]
                            : argsInside.split(",\\s*");
                    for (int i = 0; i < args.length; i++) {
                        w.write("     * @param string $arg" + (i + 1) + "\n");
                    }
                    // @return
                    String phpRet;
                    switch (mi.typeLabel) {
                        case "VOID":
                            phpRet = "void";
                            break;
                        case "STRING":
                            phpRet = "string";
                            break;
                        case "INT":
                        case "LONG":
                            phpRet = "int";
                            break;
                        case "BOOL":
                            phpRet = "bool";
                            break;
                        case "LIST":
                            phpRet = "array";
                            break;
                        case "ANY":
                        default:
                            phpRet = "any";
                            break;
                    }
                    w.write("     * @return " + phpRet + "\n");
                    w.write("     */\n");

                    // Сигнатура метода
                    w.write("    public ");
                    if (mi.isStatic) {
                        w.write("static ");
                    }
                    w.write("function ");
                    String methodName = mi.signature.substring(0, mi.signature.indexOf('('));
                    w.write(methodName + "(");
                    for (int i = 0; i < args.length; i++) {
                        w.write("$arg" + (i + 1));
                        if (i < args.length - 1) {
                            w.write(", ");
                        }
                    }
                    w.write(") {}\n\n");
                }

                w.write("}\n");
            }

            System.out.println("Generated: " + phpFile.getPath());
        }
    }

    /**
     * Повторяет оригинальные Java-классы, создавая обёртки в ./tmp/javaprepare/JTJ
     */
    private static void PrepareJavaWrappers(String jarPath, List<MethodInfo> summary) throws IOException {
        // Группируем методы по оригинальному классу
        Map<String, List<MethodInfo>> byClass = new LinkedHashMap<>();
        for (MethodInfo mi : summary) {
            byClass.computeIfAbsent(mi.className, k -> new ArrayList<>()).add(mi);
        }

        // 1) Создаём корневую папку tmp/javaprepare/JTJ
        File base = new File("tmp/javaprepare/JTJ");
        if (!base.exists()) base.mkdirs();

        // 2) Для каждого оригинального Java-класса — генерим wrapper
        for (Map.Entry<String, List<MethodInfo>> entry : byClass.entrySet()) {
            String fullClass = entry.getKey();               // e.g. com.example.Outer
            List<MethodInfo> methods = entry.getValue();
            String[] parts = fullClass.split("\\.");
            String simpleName = parts[parts.length - 1];
            String pkg       = String.join(".", Arrays.copyOf(parts, parts.length - 1));

            // Внутри base делаем директорию по package
            File outDir = new File(base, pkg.replace('.', File.separatorChar));
            outDir.mkdirs();

            File javaFile = new File(outDir, simpleName + ".java");
            try (BufferedWriter w = new BufferedWriter(new FileWriter(javaFile))) {
                // 2.1 package
                w.write("package JTJ." + pkg + ";\n");
                w.write("// Class created by JavaToJPHP (github.com/meigoc)\n\n");

                // 2.2 импорты
                w.write("import php.runtime.annotation.Reflection.Signature;\n");
                w.write("import php.runtime.lang.BaseObject;\n");
                w.write("import php.runtime.annotation.Reflection.Namespace;\n");
                w.write("import php.runtime.reflection.ClassEntity;\n");
                w.write("import php.runtime.env.Environment;\n");
                w.write("import " + fullClass + ";\n\n");

                // 2.3 объявление класса с двойным экранированием в Namespace
                String nsEscaped = pkg.replace(".", "\\\\");
                w.write("@Namespace(\"" + nsEscaped + "\")\n");
                w.write("public class " + simpleName + " extends BaseObject {\n");
                w.write("    public " + simpleName + "(Environment env) { super(env); }\n");
                w.write("    protected " + simpleName + "(ClassEntity entity) { super(entity); }\n");
                w.write("    public " + simpleName + "(Environment env, ClassEntity clazz) { super(env, clazz); }\n\n");

                // 2.4 методы
                for (int miIndex = 0; miIndex < methods.size(); miIndex++) {
                    MethodInfo mi  = methods.get(miIndex);
                    // Разбор параметров из mi.signature, который в виде "name(type1, type2)"
                    String sig    = mi.signature;
                    String name   = sig.substring(0, sig.indexOf('('));
                    String inside = sig.substring(sig.indexOf('(') + 1, sig.indexOf(')'));
                    String[] ptypes = inside.isEmpty() ? new String[0] : inside.split(",\\s*");

                    // Докблок
                    w.write("    @Signature\n");
                    w.write("    public ");
                    if (mi.isStatic) w.write("static ");
                    // возвращаемый тип в нижнем регистре для Java-обёртки
                    w.write(mi.typeLabel.toLowerCase() + " ");
                    // сигнатура Java: (ТипJava argN, ...)
                    w.write(name + " (");
                    for (int i = 0; i < ptypes.length; i++) {
                        String pt = ptypes[i];
                        // маппинг PHP-типа обратно в Java-тип
                        String javaType;
                        switch (pt) {
                            case "string":  javaType = "String";  break;
                            case "int":     javaType = "int";     break;
                            case "long":    javaType = "long";    break;
                            case "bool":    javaType = "boolean"; break;
                            case "array":   javaType = "Object[]";break;
                            default:        javaType = "Object";  break;
                        }
                        w.write(javaType + " arg" + (i+1));
                        if (i < ptypes.length - 1) w.write(", ");
                    }
                    w.write(") {\n");

                    // Тело: return OriginalClass.name(arg1, arg2);
                    w.write("        ");
                    if (!mi.typeLabel.equals("VOID")) w.write("return ");
                    // для static вызываем класс напрямую
                    w.write(fullClass + "." + name + "(");
                    for (int i = 0; i < ptypes.length; i++) {
                        w.write("arg" + (i+1));
                        if (i < ptypes.length - 1) w.write(", ");
                    }
                    w.write(");\n");

                    w.write("    }\n\n");
                }

                w.write("}\n");
            }
            System.out.println("Generated Java wrapper: " + javaFile.getPath());
        }

        // 3) Теперь главный Extension-класс
        File extDir = new File("tmp/javaprepare/JTJ/register");
        extDir.mkdirs();
        String extName = randomExtensionName(); // генерируем, например, "AbcExtension"
        File extFile = new File(extDir, extName + ".java");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(extFile))) {
            w.write("package JTJ.register;\n");
            w.write("// Class created by JavaToJPHP (github.com/meigoc)\n\n");
            w.write("import php.runtime.env.CompileScope;\n");
            w.write("import php.runtime.ext.support.Extension;\n");
            // импортируем все wrapper-классы
            for (String fullClass : byClass.keySet()) {
                String pkg = fullClass.substring(0, fullClass.lastIndexOf('.'));
                String simple = fullClass.substring(fullClass.lastIndexOf('.') + 1);
                w.write("import JTJ." + pkg + "." + simple + ";\n");
            }
            w.write("\npublic class " + extName + " extends Extension {\n");
            w.write("    public " + extName + "() {}\n\n");
            w.write("    @Override\n");
            w.write("    public Status getStatus() { return Status.EXPERIMENTAL; }\n\n");
            w.write("    @Override\n");
            w.write("    public String[] getPackageNames() { return new String[]{ \"jtj\" }; }\n\n");
            w.write("    @Override\n");
            w.write("    public void onRegister(CompileScope scope) {\n");
            for (String fullClass : byClass.keySet()) {
                String simple = fullClass.substring(fullClass.lastIndexOf('.') + 1);
                w.write("        registerClass(scope, " + simple + ".class);\n");
            }
            w.write("    }\n");
            w.write("}\n");
        }
        System.out.println("Generated Extension: " + extFile.getPath());
    }

    private static String randomExtensionName() {
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        Random rnd = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(letters.charAt(rnd.nextInt(letters.length())));
        sb.append("Extension");
        return sb.toString();
    }
}
