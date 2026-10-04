package com.anrilogger.store;
import java.nio.file.Path;
public final class CrashWriter {
    public static void main(String[] args) throws Exception {
        HistoryStore s=new HistoryStore(Path.of(args[0]));
        int committed=args.length>1?Integer.parseInt(args[1]):100;
        int pending=args.length>2?Integer.parseInt(args[2]):1;
        for(int i=0;i<committed;i++)s.append(new LogEvent(0,1000+i,i,"w","s","d",0,0,0,"p","Alice","block-break","stone",1,"","c",0,0));
        s.flush();
        for(int i=0;i<pending;i++)s.append(new LogEvent(0,2000+i,i,"w","s","d",0,0,0,"p","Alice","block-place","stone",1,"","c",0,0));
        Runtime.getRuntime().halt(0);
    }
}
