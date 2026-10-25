package top.linote.owrtinstaller;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.ZipFile;

/** Command-level uninstall regression test. Package payloads and worker scripts are data only. */
public class UninstallFlowTest {
    static int passed, failed;
    static void ok(boolean c, String s) { if (c) { passed++; System.out.println("PASS " + s); } else { failed++; System.out.println("FAIL " + s); } }
    static void fail(String s) { ok(false, s); }
    static byte[][] gpt(String rootName) {
        final long n = 1L << 24, first = 2, tail = n - 33;
        byte[] e = new byte[128 * 128]; int k = 0;
        k = part(e, k, 10000, 60000, "userdata", 1);
        k = part(e, k, 65536, 65536 + 4194303, rootName, 2);
        k = part(e, k, 9000000, 9002047, "misc", 3);
        k = part(e, k, 9010000, 9026383, "boot_a", 4);
        k = part(e, k, 9030000, 9046383, "boot_b", 5);
        byte[] p = new byte[34 * 512], q = new byte[33 * 512];
        System.arraycopy(e, 0, p, (int)(first * 512), e.length); System.arraycopy(e, 0, q, 0, e.length);
        header(p, 512, 1, n - 1, first, e, n); header(q, q.length - 512, n - 1, 1, tail, e, n);
        return new byte[][] {p, q};
    }
    static byte[][] adjacentGpt(String rootName) {
        byte[][] x=gpt(rootName); byte[] e=Arrays.copyOfRange(x[0],1024,1024+128*128);
        put64(e,40,65535); put64(e,128+32,65536); put64(e,128+40,65536+4194303);
        byte[] p=new byte[34*512],q=new byte[33*512];System.arraycopy(e,0,p,1024,e.length);System.arraycopy(e,0,q,0,e.length);
        header(p,512,1,(1L<<24)-1,2,e,1L<<24);header(q,q.length-512,(1L<<24)-1,1,(1L<<24)-33,e,1L<<24);return new byte[][]{p,q};
    }
    static int part(byte[] e, int k, long a, long b, String name, int seed) {
        int o=k*128; byte[] type=Mu300Gpt.guidLe(UUID.fromString("0fc63daf-8483-4772-8e79-3d69d8477de4"));
        System.arraycopy(type,0,e,o,16); byte[] id=Mu300Gpt.guidLe(new UUID(0x1000000000000000L+seed,seed));
        System.arraycopy(id,0,e,o+16,16); for(int i=0;i<8;i++){e[o+32+i]=(byte)(a>>(8*i));e[o+40+i]=(byte)(b>>(8*i));}
        byte[] nm=name.getBytes(StandardCharsets.UTF_16LE);System.arraycopy(nm,0,e,o+56,nm.length);return k+1;
    }
    static void put64(byte[] b,int p,long v){for(int i=0;i<8;i++)b[p+i]=(byte)(v>>(8*i));}
    static void header(byte[] raw,int at,long cur,long alt,long table,byte[] e,long sectors){byte[] h=new byte[512];System.arraycopy("EFI PART".getBytes(StandardCharsets.US_ASCII),0,h,0,8);Mu300Boot.put32le(h,8,0x10000);Mu300Boot.put32le(h,12,92);put64(h,24,cur);put64(h,32,alt);put64(h,40,34);put64(h,48,sectors-34);byte[] dg=Mu300Gpt.guidLe(UUID.fromString("11111111-2222-3333-4444-555555555555"));System.arraycopy(dg,0,h,56,16);put64(h,72,table);Mu300Boot.put32le(h,80,128);Mu300Boot.put32le(h,84,128);Mu300Boot.put32le(h,88,Mu300Boot.crc32(e,0,e.length));Mu300Boot.put32le(h,16,Mu300Boot.crc32(h,0,92));System.arraycopy(h,0,raw,at,512);}
    static byte[] zip(String f, String n) throws Exception { try (ZipFile z = new ZipFile(f); InputStream i = z.getInputStream(z.getEntry(n))) { return i.readAllBytes(); } }
    static byte[] worker() throws Exception { return Files.readAllBytes(Paths.get("app/src/main/assets/uninstall-worker.sh")); }

    /** A command-level device. It implements binary_io's hash/read protocol and never runs package code. */
    static final class Dev implements Mu300Internal.Io {
        final File root, cache; final long sectors = 1L << 24; final byte[][] gpt;
        final Map<String,byte[]> files = new LinkedHashMap<>();
        final Map<String,byte[]> remote = new LinkedHashMap<>();
        final List<String> commands = new ArrayList<>();
        byte[] staged;
        String family, rootName, slot = "_a", bootId = "boot-before";
        String cid = "0123456789abcdef0123456789abcdef";
        boolean after, rebooted, interrupted; String transport = "", transportNext = ""; boolean ready = true; byte[] misc = new byte[4096];
        byte[] bootA, bootB, userdata, rootRaw; long dataSectors = 50001, rootStart = 65536, rootEnd = 65536 + 4194303;
        Dev(File r, String fam, byte[] android, byte[] linux, byte[] rootImage) throws Exception { this(r,fam,android,linux,rootImage,false); }
        Dev(File r, String fam, byte[] android, byte[] linux, byte[] rootImage, boolean adjacent) throws Exception {
            root=r; cache=new File(r,"cache"); cache.mkdirs(); family=fam; rootName="e5".equals(fam)?"e5root":"mu300root";
            gpt=adjacent?adjacentGpt(rootName):gpt(rootName); bootA=android.clone(); bootB=linux.clone(); rootRaw=rootImage;
            if(adjacent)dataSectors=55536;
            userdata=new byte[(int)(dataSectors*512L)];
            // A minimal ext4 superblock with a capacity equal to the direct userdata mapping.
            userdata[1024+56]=0x53; userdata[1024+57]=(byte)0xef;
            long blocks=(dataSectors*512L)/4096L; put32(userdata,1024+4,blocks); put32(userdata,1024+24,2);
            for(int i=0;i<16;i++) userdata[1024+104+i]=(byte)(i+1);
            byte[] bc=new byte[32];bc[0]='_';bc[1]='a';bc[4]='B';bc[5]='C';bc[6]='A';bc[7]='B';bc[8]=1;bc[9]=2;bc[12]=(byte)0x8f;bc[14]=(byte)0x8e;Mu300Boot.put32le(bc,28,Mu300Boot.crc32(bc,0,28));System.arraycopy(bc,0,misc,2048,32);
            put(bootA,"boot_a"); put(bootB,"boot_b"); put(userdata,"userdata"); put(misc,"misc");
        }
        void put(byte[] b,String n){files.put(n,b);}
        static void put32(byte[] b,int p,long v){for(int i=0;i<4;i++)b[p+i]=(byte)(v>>(8*i));}
        static String sha(byte[] b){return Mu300Internal.hex(Mu300Internal.sha().digest(b));}
        byte[] part(String n){ if("userdata".equals(n))return userdata; if("misc".equals(n))return misc; if(n.equals(rootName)||n.equals("mmcblk0p2"))return rootRaw; byte[] v=files.get(n); if(v!=null)return v; if(n.matches("(?:uboot|sml|teecfg|trustos|vbmeta)_[ab]"))return new byte[1048576]; return null; }
        byte[] disk(long skip,long count){
            byte[] out=new byte[(int)(count*512)]; byte[] first=gpt[0], last=gpt[1];
            for(long i=0;i<count;i++){long s=skip+i; if(s<34)System.arraycopy(first,(int)(s*512),out,(int)(i*512),512); else if(s>=sectors-33)System.arraycopy(last,(int)((s-(sectors-33))*512),out,(int)(i*512),512);} return out;
        }
        byte[] eval(String c) throws IOException {
            Matcher disk=Pattern.compile("dd if=/dev/(?:block/)?mmcblk0(?:p39)? bs=1024 skip=1 count=1.*").matcher(c); if(disk.find())return Arrays.copyOfRange(userdata,1024,2048);
            disk=Pattern.compile("dd if=/dev/block/mmcblk0 bs=512(?: skip=(\\d+))? count=(\\d+).*").matcher(c);
            if(disk.find())return disk(disk.group(1)==null?0:Long.parseLong(disk.group(1)),Long.parseLong(disk.group(2)));
            Matcher cat=Pattern.compile("cat /dev/block/by-name/(\\w+).*\\|?").matcher(c); if(cat.find()){byte[] p=part(cat.group(1)); if(p==null)throw new IOException("missing "+cat.group(1)); return p;}
            Matcher m=Pattern.compile("dd if=/dev/block/(?:by-name/)?([A-Za-z0-9_]+) bs=(\\d+)(?: skip=(\\d+))? count=(\\d+).*").matcher(c);
            if(m.find()){String n=m.group(1);int bs=Integer.parseInt(m.group(2)),sk=m.group(3)==null?0:Integer.parseInt(m.group(3)),co=Integer.parseInt(m.group(4));byte[] p=part(n);if(p==null)throw new IOException("missing "+n);return Arrays.copyOfRange(p,sk*bs,Math.min(p.length,(sk+co)*bs));}
            m=Pattern.compile("dd if=/dev/block/by-name/(\\w+) bs=1 skip=2048 count=32.*").matcher(c); if(m.find())return Arrays.copyOfRange(misc,2048,2080);
            m=Pattern.compile("dd if=/dev/block/by-name/(\\w+) bs=2048 count=1.*").matcher(c); if(m.find())return Arrays.copyOf(misc,2048);
            throw new IOException("unexpected binary read: "+c);
        }
        String protocol(String script) throws IOException {
            int a=script.indexOf("(")+1,b=script.indexOf(") > ",a); String inner=script.substring(a,b); staged=eval(inner); String h=sha(staged); return staged.length+"\n"+h+"  /tmp/x\n"+h+"  -\n";
        }
        @Override public String sh(String s,boolean root) throws IOException { return sh(s,root,0); }
        @Override public String sh(String s,boolean root,int silence) throws IOException {
            commands.add(s);
            try {
                if(s.contains("OWRT-PHASE hash") && s.contains("sha256sum")) return protocol(s);
                if(s.startsWith("df -Pk /data/local/tmp")) return "999999999\n";
                if(s.startsWith("readlink -f /dev/block/mmcblk0p39")) return "/dev/mmcblk0p39\n";
                if(s.startsWith("cat /sys/class/block/mmcblk0p39/size")) return dataSectors+"\n";
                if(s.startsWith("umask 077; mkdir")) return "";
                if(s.startsWith("cat /dev/block/by-name/")){String n=s.substring(25).trim(); byte[] p=part(n); if(p==null)throw new IOException("missing "+n); return new String(p,StandardCharsets.ISO_8859_1);}
                if(s.startsWith("blockdev --getsize64 /dev/block/by-name/")){String n=s.substring(40).trim(); byte[] p=part(n); if(p==null)throw new IOException("missing "+n); return p.length+"\n";}
                if(s.startsWith("sha256sum /dev/block/by-name/")){String[] ns=s.substring(29).split(" ");StringBuilder o=new StringBuilder();for(String x:ns){x=x.replace("/dev/block/by-name/","");byte[] p=part(x);o.append(sha(p)).append("  ").append(x).append("\n");}return o.toString();}
                Matcher z=Pattern.compile("blockdev --getsize64 /dev/block/by-name/(\\w+)").matcher(s);if(z.matches())return String.valueOf(part(z.group(1)).length)+"\n";
                if(s.startsWith("set -e; d=$(readlink -f /dev/block/by-name/userdata)")) return "NODE=mmcblk0p39\nCID="+cid+"\nSTART=10000\nSIZE="+dataSectors+"\nDISK_SIZE="+sectors+"\nFREE=999999999\nSTATE=unencrypted\nTYPE=\nSOURCE=/dev/block/mmcblk0p39\nROOT="+(after?"":"mmcblk0p2")+"\nROOT_START="+rootStart+"\nROOT_SIZE="+(rootEnd-rootStart+1)+"\nSLOT="+slot+"\nBOOTED=1\nSETUP=1\nMODEL=E5\nBOOT_ID="+bootId+"\n";
                if(s.startsWith("set -e; n=mmcblk0p2;"))return "IDLE\n";
                if(s.contains("dd if=") && s.contains("of=") && s.contains("boot.img")){byte[] im=remote.get(findRemote(s,"boot.img"));files.put("boot_b",im.clone());if(interrupted)throw new IOException("mock interruption after boot write");return "";}
                if(s.contains("trial.bin") && s.contains("seek=2048")){byte[] t=remote.get(findRemote(s,"trial.bin"));System.arraycopy(t,0,misc,2048,32);return "";}
                if(s.contains("android-bc") && s.contains("of=/dev/block/by-name/misc")){byte[] t=remote.get(findRemote(s,"android-bc"));System.arraycopy(t,0,misc,2048,32);files.put("boot_b",bootA.clone());return "";}
                if(s.startsWith("rm -f "))return "";
                if(s.equals("getprop ro.boot.slot_suffix"))return slot+"\n";
                if(s.startsWith("getprop ro.product.model"))return "E5\ne5\n";
                throw new IOException("unexpected command: "+s);
            } catch (RuntimeException e){throw new IOException(e);}
        }
        String findRemote(String s,String n){int p=s.indexOf("/openwrt-uninstall-");int q=s.indexOf("/",p+1);String k=s.substring(p,q)+"/"+n;if(remote.containsKey(k))return k;for(String x:remote.keySet())if(x.endsWith("/"+n))return x;return k;}
        @Override public void pull(String r,File d)throws IOException{Mu300Internal.writeAll(d,staged!=null?staged:remote.get(r));}
        @Override public void push(File l,String r)throws IOException{remote.put(r,Mu300Internal.readAll(l));}
        @Override public void reboot(){rebooted=true;}
        @Override public String transport(){return transport;}
        @Override public boolean ready(){return ready;}
        @Override public void log(String s){}
        @Override public void progress(int p){}
        @Override public File cacheDir(){return cache;}
    }

    static byte[] makeRoot(File w, String family) throws Exception {
        File tree=new File(w,"tree"); Mu300Internal.deleteTree(tree); tree.mkdirs();
        String[] paths="e5".equals(family)?new String[]{"etc/e5/part-root","etc/openwrt_release"}:new String[]{"openwrt/etc/mu300/desktop-mode",".mu300/boot-os","openwrt/etc/openwrt_release"};
        for(String x:paths){File f=new File(tree,x);f.getParentFile().mkdirs();Files.writeString(f.toPath(),x.endsWith("desktop-mode")?"part\n":x.endsWith("part-root")?"e5root /dev/mmcblk0\n":x.endsWith("boot-os")?"android\n":"DISTRIB_ID='OpenWrt'\n");}
        File img=new File(w,"root.ext4"); Process q=new ProcessBuilder("truncate","-s","64M",img.getAbsolutePath()).start();if(q.waitFor()!=0)throw new IOException("truncate");q=new ProcessBuilder("mkfs.ext4","-q","-F","-d",tree.getAbsolutePath(),img.getAbsolutePath()).start();if(q.waitFor()!=0)throw new IOException("mkfs.ext4");return Files.readAllBytes(img.toPath());
    }
    static byte[] installedLinux(String family,String zip) throws Exception {
        byte[] b=zip(zip,"e5".equals(family)?"files/boot.img":"payload/boot-linux.img");
        if("mu300".equals(family)){LinkedHashMap<String,byte[]> o=new LinkedHashMap<>();o.put("etc/mu300-desktop-root-mode","part\n".getBytes(StandardCharsets.US_ASCII));b=UninstallBoot.build(b,family,o,worker());String c=new String(b,44,1536,StandardCharsets.ISO_8859_1).replace(" openwrt.uninstall=1","").trim();Arrays.fill(b,44,1580,(byte)0);byte[] x=c.getBytes(StandardCharsets.US_ASCII);System.arraycopy(x,0,b,44,x.length);}return b;
    }
    static Uninstall.Pkg pkg2(String family,String zip) throws Exception {Uninstall.Pkg p=new Uninstall.Pkg();p.family=family;p.base=zip(zip,"e5".equals(family)?"files/boot.img":"payload/boot-linux.img");p.worker=worker();if("e5".equals(family))p.ext4Tar=()->{try{return zip(zip,"files/ext4-tools.tar");}catch(Exception e){throw new IOException(e);}};return p;}
    static Uninstall.Prepared plan(Dev d,String family,String zip)throws Exception {Uninstall.Pkg pk=pkg2(family,zip);Uninstall.Prepared p=new Uninstall.Prepared();p.device=Uninstall.probe(d,d.rootName,"e5");p.front=34;p.tail=d.sectors-33;p.total=d.sectors;p.rootStart=d.rootStart;p.rootSize=d.rootEnd-d.rootStart+1;p.before=d.gpt;p.after=Uninstall.deviceLayout(d.gpt[0],d.gpt[1],d.sectors,d.rootName).after;p.action="gap";p.wipe=false;p.openwrtBytes=p.rootSize*512;p.androidBytes=d.dataSectors*512;p.previousAndroidBytes=p.androidBytes;p.proof=Uninstall.evidence(d,p.device,pk,Uninstall.partition(Uninstall.space(d.gpt[0],d.gpt[1],d.sectors),d.rootName),null);p.volume=new Uninstall.Volume();p.volume.filesystem="unknown";p.nodes.put("userdata","mmcblk0p1");p.nodes.put(d.rootName,"mmcblk0p2");p.nodes.put("misc","mmcblk0p3");p.nodes.put("boot_a","mmcblk0p4");p.nodes.put("boot_b","mmcblk0p5");return p;}
    static void safetyCases(File work,String family,String zip,byte[] android,byte[] linux,byte[] root)throws Exception {
        Uninstall.Pkg pk=pkg2(family,zip);
        File w=new File(work,family+"-safety");Mu300Internal.deleteTree(w);w.mkdirs();Dev d=new Dev(w,family,android,linux,root);File rr=new File(w,"records");rr.mkdirs();Uninstall.Prepared p=plan(d,family,zip);
        UninstallRun.start(d,rr,p,pk,null,false);File dir=UninstallRun.pending(rr);d.bootId="changed-without-offline";int writes=0;for(String c:d.commands)if(c.contains(" of="))writes++;try{UninstallRun.resume(d,dir,pk);fail(family+" resume without offline effect was accepted");}catch(Uninstall.Refused e){ok(e.getMessage().contains("offline")||e.getMessage().contains("未完成"),family+" resume without offline effect gives clear refusal");}int writes2=0;for(String c:d.commands)if(c.contains(" of="))writes2++;ok(writes==writes2,family+" no-effect resume performs no partition write");
        File ww=new File(work,family+"-wipe");Mu300Internal.deleteTree(ww);ww.mkdirs();Dev dw=new Dev(ww,family,android,linux,root);File rw=new File(ww,"records");rw.mkdirs();Uninstall.Prepared pw=plan(dw,family,zip);pw.wipe=true;pw.action="reset";pw.fingerprint="approved";try{UninstallRun.start(dw,rw,pw,pk,"wrong",false);fail(family+" wipe without approval was accepted");}catch(Uninstall.Refused e){ok(true,family+" wipe without approved fingerprint refused");}int wc=0;for(String c:dw.commands)if(c.contains(" of="))wc++;ok(wc==0,family+" wipe refusal performs zero partition writes");
        UninstallRun.Outcome wo=UninstallRun.start(dw,rw,pw,pk,"approved",false);ok(wo.pending&&dw.rebooted,family+" wipe with matching non-local approval proceeds");
        File wa=new File(work,family+"-adjacent");Mu300Internal.deleteTree(wa);wa.mkdirs();Dev da=new Dev(wa,family,android,linux,root,true);File ra=new File(wa,"records");ra.mkdirs();Uninstall.Prepared pa=plan(da,family,zip);Uninstall.Volume av=Uninstall.androidVolume(da,pa.device,false);pa.volume=av;pa.action="grow-ext4";pa.wipe=false;pa.previousAndroidBytes=da.dataSectors*512;pa.androidBytes=(da.rootEnd-da.rootStart+1+da.dataSectors)*512;if("mu300".equals(family)){pk.repairDir=new File(wa,"repair");pk.repairDir.mkdirs();try(ZipFile z=new ZipFile(zip)){java.util.Enumeration<? extends java.util.zip.ZipEntry> en=z.entries();while(en.hasMoreElements()){ZipFile zz=z;java.util.zip.ZipEntry ze=en.nextElement();String n=ze.getName();String pre="desktop/files/boot/repair/";if(n.startsWith(pre)&&!ze.isDirectory()){File f=new File(pk.repairDir,n.substring(pre.length()));f.getParentFile().mkdirs();try(InputStream in=zz.getInputStream(ze)){Files.write(f.toPath(),in.readAllBytes());}}}}pk.repairSums=new LinkedHashMap<>();pk.repairSums.put("desktop/files/boot/repair/manifest.json",Mu300Boot.sha256(Files.readAllBytes(new File(pk.repairDir,"manifest.json").toPath())));}pa.tools=Uninstall.growthTools(pk,av.filesystem);for(Map.Entry<String,byte[]> e:pa.tools.entrySet())pa.toolHashes.put(e.getKey(),Mu300Boot.sha256(e.getValue()));UninstallRun.Outcome ao=UninstallRun.start(da,ra,pa,pk,null,false);File ad=UninstallRun.pending(ra);da.after=true;da.bootId="grown";da.dataSectors=pa.androidBytes/512;Dev.put32(da.userdata,1024+4,da.dataSectors*512L/4096L);da.gpt[0]=pa.after[0];da.gpt[1]=pa.after[1];da.files.put("boot_b",android.clone());ao=UninstallRun.resume(da,ad,pk);ok(ao.complete&&!ao.pending,family+" adjacent root growth completes after simulated offline data growth");
    }
    static void family(File work,String family,String zip)throws Exception{File w=new File(work,family);Mu300Internal.deleteTree(w);w.mkdirs();byte[] a=installedLinux(family,zip),root=makeRoot(w,family);byte[] android=new byte[UninstallBoot.BOOT_BYTES];System.arraycopy("ANDROID!".getBytes(StandardCharsets.US_ASCII),0,android,0,8);Dev d=new Dev(w,family,android,a,root);d.transport="usb:SER1";Uninstall.Pkg pk=pkg2(family,zip);Uninstall.Prepared p=plan(d,family,zip);File records=new File(w,"records");records.mkdirs();UninstallRun.Outcome o=UninstallRun.start(d,records,p,pk,null,false);ok(o.pending&&!o.complete&&d.rebooted,family+" start arms and requests reboot");File dir=UninstallRun.pending(records);ok(new File(dir,"backup/gpt-first.bin").isFile()&&new File(dir,"backup/misc-head.bin").isFile(),family+" persists pre-write backups");
        byte[] bg=Files.readAllBytes(new File(dir,"backup/gpt-first.bin").toPath()), bm=Files.readAllBytes(new File(dir,"backup/misc-head.bin").toPath());
        ok(Arrays.equals(bg,d.disk(0,d.gpt[0].length/512))&&Arrays.equals(bm,Arrays.copyOf(d.misc,4096)),family+" backups retain exact pre-write bytes");
        byte[] built=Files.readAllBytes(new File(dir,"boot-uninstall.img").toPath());ok(Arrays.equals(built,d.files.get("boot_b")),family+" Linux slot receives exact locally built uninstall image");
        Map<String,Object> first=UninstallRun.load(dir).r;ok("usb:SER1".equals(String.valueOf(first.get("transport"))),family+" records initial transport");
        d.transport="tcp:192.168.0.1:5555";d.after=true;d.bootId="boot-after";d.gpt[0]=p.after[0];d.gpt[1]=p.after[1];d.files.put("boot_b",android.clone());o=UninstallRun.resume(d,dir,pk);Map<String,Object> rebound=UninstallRun.load(dir).r;ok(o.complete&&!o.pending,family+" resumes after offline layout");ok("tcp:192.168.0.1:5555".equals(String.valueOf(rebound.get("transport")))&&rebound.get("transport_history") instanceof List&&((List<?>)rebound.get("transport_history")).size()==1,family+" persists one transport history entry");ok(UninstallRun.pending(records)==null,family+" completion removes pending record");
        // Interrupted arm: production recovery must verify the already-written image and avoid replaying GPT writes.
        File wi=new File(work,family+"-interrupted");Mu300Internal.deleteTree(wi);wi.mkdirs();Dev di=new Dev(wi,family,android,a,root);di.transport="usb:SER1";File ri=new File(wi,"records");ri.mkdirs();Uninstall.Prepared pi=plan(di,family,zip);di.interrupted=true;try{UninstallRun.start(di,ri,pi,pk,null,false);fail(family+" interrupted arm unexpectedly succeeded");}catch(IOException expected){ok(true,family+" interrupted arm stops after boot write");}File id=UninstallRun.pending(ri);di.interrupted=false;di.bootId="boot-retry";UninstallRun.Outcome ro=UninstallRun.resume(di,id,pk);ok(ro.pending&&di.files.get("boot_b")!=null,family+" recoverArm retries without double damage");
    }
    public static void main(String[] args)throws Exception{
        File work=new File(args.length>0?args[0]:"build-test/uninstall-flow");Mu300Internal.deleteTree(work);work.mkdirs();String e=args.length>1?args[1]:"/home/ubuntu/upload/e5-openwrt-25.12.5-r20261023.183851-g33f8a737-s4bbbcbec-k6.1(1).zip",m=args.length>2?args[2]:"/home/ubuntu/upload/mu300-openwrt-25.12.5-r20261024.082138-g3c350105-sc498f186-k.zip";
        for(String[] x:new String[][]{{"e5",e},{"mu300",m}})try{family(work,x[0],x[1]);File sw=new File(work,x[0]+"-scenario-assets");sw.mkdirs();byte[] a=installedLinux(x[0],x[1]),r=makeRoot(sw,x[0]),an=new byte[UninstallBoot.BOOT_BYTES];System.arraycopy("ANDROID!".getBytes(StandardCharsets.US_ASCII),0,an,0,8);safetyCases(work,x[0],x[1],an,a,r);}catch(Throwable t){t.printStackTrace();fail(x[0]+" flow: "+t.getMessage());}
        System.out.println("UninstallFlowTest: "+passed+" passed, "+failed+" failed");if(failed!=0)System.exit(1);
    }
}
