import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.util.*;

/** Allocation weights are estimates of bytes allocated, never retained heap sizes. */
public final class AnalyzeJeiMemoryJfr {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]);
        Map<String,Long> stages=new HashMap<>();
        for(String line:Files.readAllLines(root.resolve("stages.csv"))) {
            String[] cells=line.strip().split(",",2);
            if(cells.length==2) stages.put(cells[1],Long.parseLong(cells[0].replace("\ufeff","")));
        }
        long from=stages.get("world-requested"), to=stages.get("ready");
        Map<String,Long> allocationClasses=new HashMap<>(), allocationSites=new HashMap<>();
        long allocatedWeight=0,jeiWeight=0,jetWeight=0,heapPeak=0,gcPause=0;
        int gcCount=0,samples=0;
        try(RecordingFile recording=new RecordingFile(root.resolve("recording.jfr"))) {
            while(recording.hasMoreEvents()) {
                RecordedEvent e=recording.readEvent();
                long time=e.getStartTime().toEpochMilli();
                if(time<from || time>to) continue;
                switch(e.getEventType().getName()) {
                    case "jdk.ObjectAllocationSample" -> {
                        long weight=e.getLong("weight"); allocatedWeight+=weight; samples++;
                        allocationClasses.merge(e.getClass("objectClass").getName(),weight,Long::sum);
                        boolean jei=false,jet=false;
                        String site="<no Java stack>";
                        if(e.getStackTrace()!=null) {
                            for(RecordedFrame frame:e.getStackTrace().getFrames()) {
                                String type=frame.getMethod().getType().getName();
                                jei|=type.startsWith("mezz.jei.");
                                jet|=type.startsWith("com.tonywww.jeioptimize.");
                                if(site.equals("<no Java stack>") && !type.startsWith("java.") && !type.startsWith("jdk.") && !type.startsWith("sun."))
                                    site=type+"."+frame.getMethod().getName();
                            }
                        }
                        if(jei) jeiWeight+=weight;
                        if(jet) jetWeight+=weight;
                        allocationSites.merge(site,weight,Long::sum);
                    }
                    case "jdk.GCHeapSummary" -> heapPeak=Math.max(heapPeak,e.getLong("heapUsed"));
                    case "jdk.GarbageCollection" -> {gcCount++;gcPause+=e.getDuration("sumOfPauses").toNanos();}
                }
            }
        }
        StringBuilder text=new StringBuilder("metric,value,unit\n");
        text.append("window,").append((to-from)/1000.0).append(",s\n")
            .append("allocationSampleCount,").append(samples).append(",samples\n")
            .append("allocationSampleWeight,").append(allocatedWeight).append(",estimated allocated bytes\n")
            .append("allocationWithJeiStack,").append(jeiWeight).append(",estimated allocated bytes\n")
            .append("allocationWithJetStack,").append(jetWeight).append(",estimated allocated bytes; overlaps JEI\n")
            .append("heapPeakAtGcEvents,").append(heapPeak).append(",bytes\n")
            .append("gcCount,").append(gcCount).append(",collections\n")
            .append("gcTotalPause,").append(gcPause/1e6).append(",ms\n");
        Files.writeString(root.resolve("jfr-memory-metrics.csv"),text);
        table(root.resolve("allocation-classes.csv"),allocationClasses);
        table(root.resolve("allocation-sites.csv"),allocationSites);
        System.out.print(text);
    }
    static void table(Path path,Map<String,Long> values)throws Exception {
        StringBuilder out=new StringBuilder("name,estimatedAllocatedBytes\n");
        values.entrySet().stream().sorted(Map.Entry.<String,Long>comparingByValue().reversed()).limit(100)
            .forEach(e->out.append('"').append(e.getKey().replace("\"","\"\"")).append("\",").append(e.getValue()).append('\n'));
        Files.writeString(path,out);
    }
}
