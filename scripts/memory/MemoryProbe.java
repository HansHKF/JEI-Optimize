import java.lang.instrument.Instrumentation;
import java.lang.management.ManagementFactory;
import java.lang.reflect.*;
import java.nio.file.*;
import java.io.*;
import java.util.concurrent.*;

/** Local diagnostic agent, never included in a mod artifact. */
public final class MemoryProbe {
    public static void premain(String directory, Instrumentation instrumentation) throws Exception {
        Path output = Path.of(directory);
        Files.createDirectories(output);
        Files.writeString(output.resolve("process.pid"), Long.toString(ProcessHandle.current().pid()));
        Thread worker = new Thread(() -> {
            try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(output.resolve("heap-samples.csv")))) {
                out.println("epochMs,uptimeMs,used,committed,max,nonHeapUsed,gcCount,gcMillis");
                var beans = ManagementFactory.getGarbageCollectorMXBeans();
                var memory = ManagementFactory.getMemoryMXBean();
                Class<?> minecraftType = null;
                int samples = 0;
                while (true) {
                    var heap = memory.getHeapMemoryUsage();
                    out.printf("%d,%d,%d,%d,%d,%d,%d,%d%n", System.currentTimeMillis(),
                        ManagementFactory.getRuntimeMXBean().getUptime(), heap.getUsed(), heap.getCommitted(),
                        heap.getMax(), memory.getNonHeapMemoryUsage().getUsed(),
                        beans.stream().mapToLong(b -> Math.max(0,b.getCollectionCount())).sum(),
                        beans.stream().mapToLong(b -> Math.max(0,b.getCollectionTime())).sum());
                    out.flush();
                    if (++samples % 8 == 0 && Files.exists(output.resolve("inspect-enabled.txt"))) {
                        if (minecraftType == null) {
                            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                                if (type.getName().equals("net.minecraft.client.Minecraft")) { minecraftType=type; break; }
                            }
                        }
                        if (minecraftType != null) {
                            Thread.currentThread().setContextClassLoader(minecraftType.getClassLoader());
                            try {
                                Object client = instance(minecraftType);
                                Object screen = null, level = null;
                                for (Field field : minecraftType.getFields()) {
                                    if (field.getType().getName().equals("net.minecraft.client.gui.screens.Screen")) screen=field.get(client);
                                    if (field.getType().getName().equals("net.minecraft.client.multiplayer.ClientLevel")) level=field.get(client);
                                }
                                Files.writeString(output.resolve("state.txt"), "level="+(level!=null)+"\nscreen="+(screen==null?"null":screen.getClass().getName()));
                            } catch (Throwable notReady) {}
                        }
                    }
                    Path control = output.resolve("command.txt");
                    if (Files.exists(control)) {
                        String command = Files.readString(control).trim();
                        Files.delete(control);
                        if (minecraftType == null) {
                            for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                                if (type.getName().equals("net.minecraft.client.Minecraft")) { minecraftType=type; break; }
                            }
                        }
                        if (minecraftType == null) throw new IllegalStateException("Minecraft has not loaded");
                        Class<?> mcType = minecraftType;
                        Thread.currentThread().setContextClassLoader(mcType.getClassLoader());
                        Object client = instance(mcType);
                        if (client == null) throw new IllegalStateException("No Minecraft singleton");
                        FutureTask<Void> action = new FutureTask<>(() -> {
                            if (command.startsWith("join ")) {
                                Object flows = null;
                                for (Method method : mcType.getMethods()) {
                                    if (method.getParameterCount()==0 && method.getReturnType().getSimpleName().equals("WorldOpenFlows")) {
                                        flows = method.invoke(client); break;
                                    }
                                }
                                if (flows == null) throw new IllegalStateException("WorldOpenFlows method missing");
                                Class<?> screen = Class.forName("net.minecraft.client.gui.screens.Screen", false, mcType.getClassLoader());
                                Object menu = Class.forName("net.minecraft.client.gui.screens.TitleScreen", true, mcType.getClassLoader()).getConstructor().newInstance();
                                boolean called = false;
                                for (Method method : flows.getClass().getMethods()) {
                                    Class<?>[] types = method.getParameterTypes();
                                    if (types.length==2 && types[0]==screen && types[1]==String.class) {
                                        method.invoke(flows,menu,command.substring(5)); called=true; break;
                                    }
                                    if (types.length==2 && types[0]==String.class && types[1]==Runnable.class) {
                                        method.invoke(flows,command.substring(5),(Runnable)()->{}); called=true; break;
                                    }
                                }
                                if (!called) throw new IllegalStateException("World loading method missing: "+java.util.Arrays.toString(flows.getClass().getMethods()));
                            } else if (command.equals("leave")) {
                                Object level = null;
                                for (Field field : mcType.getFields()) {
                                    if (field.getType().getName().equals("net.minecraft.client.multiplayer.ClientLevel")) level=field.get(client);
                                }
                                if (level != null) {
                                    try { level.getClass().getMethod("m_7462_").invoke(level); }
                                    catch (NoSuchMethodException modern) {
                                        Class<?> component=Class.forName("net.minecraft.network.chat.Component",false,mcType.getClassLoader());
                                        Object message=level.getClass().getField("DEFAULT_QUIT_MESSAGE").get(null);
                                        level.getClass().getMethod("disconnect",component).invoke(level,message);
                                    }
                                }
                                Class<?> screen = Class.forName("net.minecraft.client.gui.screens.Screen", false, mcType.getClassLoader());
                                Class<?> title = Class.forName("net.minecraft.client.gui.screens.TitleScreen", true, mcType.getClassLoader());
                                Object menu = title.getConstructor().newInstance();
                                try { mcType.getMethod("disconnect", screen, boolean.class).invoke(client, menu, false); }
                                catch (NoSuchMethodException older) { mcType.getMethod("m_91320_",screen).invoke(client,menu); }
                            } else if (command.equals("stop")) {
                                try { mcType.getMethod("stop").invoke(client); }
                                catch (NoSuchMethodException older) { mcType.getMethod("m_91395_").invoke(client); }
                            } else throw new IllegalArgumentException(command);
                            return null;
                        });
                        mcType.getMethod("execute", Runnable.class).invoke(client, action);
                        action.get(300,TimeUnit.SECONDS);
                        Files.writeString(output.resolve("command-result.txt"), command+" completed");
                    }
                    Thread.sleep(250);
                }
            } catch (Throwable failure) {
                try (PrintWriter errors = new PrintWriter(Files.newBufferedWriter(output.resolve("probe-error.txt")))) {
                    failure.printStackTrace(errors);
                } catch (IOException ignored) {}
            }
        }, "jet-memory-diagnostic");
        worker.setDaemon(true);
        worker.start();
    }

    private static Object instance(Class<?> type) throws Exception {
        for (Method method : type.getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers()) && method.getParameterCount()==0 && method.getReturnType()==type) {
                method.setAccessible(true); return method.invoke(null);
            }
        }
        return null;
    }
}
