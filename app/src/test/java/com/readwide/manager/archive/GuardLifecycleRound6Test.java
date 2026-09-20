package com.readwide.manager.archive;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

public class GuardLifecycleRound6Test {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private final byte[] original={3,4,5},replacement={7,8,9};
    private File backup(RarOutputFileGuard guard)throws Exception{
        Field f=RarOutputFileGuard.class.getDeclaredField("backupFile");f.setAccessible(true);return (File)f.get(guard);
    }
    private File target()throws Exception{File f=temp.newFile();Files.write(f.toPath(),original);return f;}
    private void check(File f,byte[] bytes)throws Exception{assertArrayEquals(bytes,Files.readAllBytes(f.toPath()));}
    private void nameLimit(String name)throws Exception{
        File dir=temp.newFolder(),ordinary=new File(dir,name);Files.write(ordinary.toPath(),original);
        // Deterministic NAME_MAX model. Actual host filesystems need not share
        // Android's name limits; this does not pretend to be a device test.
        File limited=new File(ordinary.getPath()){
            @Override public boolean renameTo(File dest){return dest.getName().getBytes(StandardCharsets.UTF_8).length<=255&&super.renameTo(dest);}
        };
        try(RarOutputFileGuard guard=RarOutputFileGuard.forTarget(limited)){
            Files.write(limited.toPath(),replacement);guard.commit();
        }
        check(ordinary,replacement);assertEquals(1,dir.list().length);
    }
    @Test public void longAsciiTargetCanBeReplaced()throws Exception{nameLimit("x".repeat(235)+".bin");}
    @Test public void longUnicodeTargetCanBeReplaced()throws Exception{nameLimit("한".repeat(78)+".bin");}
    @Test public void backupNamesAreShortAndIndependentOfTarget()throws Exception{
        File out=new File(temp.newFolder(),"long".repeat(50));Files.write(out.toPath(),original);
        try(RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out)){
            assertTrue(backup(guard).getName().getBytes(StandardCharsets.UTF_8).length<=64);
        }check(out,original);
    }
    @Test public void committedBackupDeletionFailureIsVisibleAndRetryable()throws Exception{
        File out=target();RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out);File actual=backup(guard);
        File failOnce=new File(actual.getPath()){boolean failed;@Override public boolean delete(){if(!failed){failed=true;return false;}return super.delete();}};
        Field field=RarOutputFileGuard.class.getDeclaredField("backupFile");field.setAccessible(true);field.set(guard,failOnce);
        Files.write(out.toPath(),replacement);guard.commit();assertThrows(IOException.class,guard::close);
        check(out,replacement);check(actual,original);guard.close();guard.close();check(out,replacement);assertFalse(actual.exists());
    }
    @Test public void missingRecoveryCopyDoesNotDeleteCurrentFile()throws Exception{
        File out=target();RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out);
        Files.delete(backup(guard).toPath());Files.write(out.toPath(),replacement);
        assertThrows(IOException.class,guard::close);check(out,replacement);
    }
    @Test public void rollbackCleanupRetryDoesNotDeleteRestoredOriginal()throws Exception{
        File out=target();RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out);
        File unexpected=new File(backup(guard).getParentFile(),"cleanup-blocker");Files.write(unexpected.toPath(),new byte[]{1});
        Files.write(out.toPath(),replacement);assertThrows(IOException.class,guard::close);check(out,original);
        Files.delete(unexpected.toPath());guard.close();guard.close();check(out,original);
    }
    @Test public void committedCleanupRetryDoesNotTouchFinalOutput()throws Exception{
        File out=target();RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out);
        File unexpected=new File(backup(guard).getParentFile(),"cleanup-blocker");Files.write(unexpected.toPath(),new byte[]{1});
        Files.write(out.toPath(),replacement);guard.commit();assertThrows(IOException.class,guard::close);check(out,replacement);
        Files.delete(unexpected.toPath());guard.close();check(out,replacement);
    }
    @Test public void cannotCommitAfterRollbackHasStarted()throws Exception{
        File ordinary=target();File failOnce=new File(ordinary.getPath()){
            boolean failed;@Override public boolean delete(){if(!failed){failed=true;return false;}return super.delete();}
        };
        RarOutputFileGuard guard=RarOutputFileGuard.forTarget(failOnce);Files.write(ordinary.toPath(),replacement);
        assertThrows(IOException.class,guard::close);assertThrows(IllegalStateException.class,guard::commit);
        guard.close();check(ordinary,original);
    }
    @Test public void failedInitialRenameLeavesNoReservation()throws Exception{
        File ordinary=target(),parent=ordinary.getParentFile();
        File fail=new File(ordinary.getPath()){@Override public boolean renameTo(File dest){return false;}};
        assertThrows(IOException.class,()->RarOutputFileGuard.forTarget(fail));check(ordinary,original);assertEquals(1,parent.list().length);
    }
    @Test public void duplicateTargetsRollBackInReverseOrder()throws Exception{
        File out=target();RarOutputFileGuard first=RarOutputFileGuard.forTarget(out);Files.write(out.toPath(),replacement);
        RarOutputFileGuard second=RarOutputFileGuard.forTarget(out);Files.write(out.toPath(),new byte[]{0});
        second.close();check(out,replacement);first.close();check(out,original);assertEquals(1,out.getParentFile().list().length);
    }
    @Test public void concurrentDistinctTargetsDoNotLeakBackups()throws Exception{
        File dir=temp.newFolder();ExecutorService pool=Executors.newFixedThreadPool(6);List<Future<?>> jobs=new ArrayList<>();
        try{
            for(int i=0;i<129;i++){final int index=i;File out=new File(dir,"file"+i);Files.write(out.toPath(),original);
                jobs.add(pool.submit(()->{try(RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out)){
                    Files.write(out.toPath(),replacement);if((index&1)==0)guard.commit();
                }catch(IOException ex){throw new UncheckedIOException(ex);}}));
            }
            for(Future<?> job:jobs)job.get();
        }finally{pool.shutdownNow();assertTrue(pool.awaitTermination(10,TimeUnit.SECONDS));}
        for(int i=0;i<129;i++)check(new File(dir,"file"+i),(i&1)==0?replacement:original);
        assertEquals(129,dir.list().length);
    }
    @Test public void directoryTargetRejectedWithoutMutation()throws Exception{
        File dir=temp.newFolder();assertThrows(IOException.class,()->RarOutputFileGuard.forTarget(dir));assertTrue(dir.isDirectory());assertEquals(0,dir.list().length);
    }
    @Test public void initialRenameSecurityFailureCleansReservation()throws Exception{
        File ordinary=target();File fail=new File(ordinary.getPath()){@Override public boolean renameTo(File dest){throw new SecurityException("injected");}};
        assertThrows(IOException.class,()->RarOutputFileGuard.forTarget(fail));check(ordinary,original);assertEquals(1,ordinary.getParentFile().list().length);
    }
    @Test public void cleanupSecurityFailureIsCheckedAndRetryable()throws Exception{
        File out=target();RarOutputFileGuard guard=RarOutputFileGuard.forTarget(out);File actual=backup(guard);
        File failOnce=new File(actual.getPath()){boolean failed;@Override public boolean delete(){if(!failed){failed=true;throw new SecurityException("injected");}return super.delete();}};
        Field field=RarOutputFileGuard.class.getDeclaredField("backupFile");field.setAccessible(true);field.set(guard,failOnce);
        Files.write(out.toPath(),replacement);guard.commit();assertThrows(IOException.class,guard::close);check(out,replacement);
        guard.close();assertFalse(actual.exists());check(out,replacement);
    }

}
