package robust.gradle.plugin.asm;

import com.android.utils.AsmUtils;
import com.meituan.robust.ChangeQuickRedirect;
import com.meituan.robust.Constants;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipOutputStream;

import javassist.CannotCompileException;
import javassist.CtClass;
import robust.gradle.plugin.InsertcodeStrategy;


/**
 * Created by zhangmeng on 2017/5/10.
 */

public class AsmInsertImpl extends InsertcodeStrategy {


    public AsmInsertImpl(List<String> hotfixPackageList, List<String> hotfixMethodList, List<String> exceptPackageList, List<String> exceptMethodList, boolean isHotfixMethodLevel, boolean isExceptMethodLevel) {
        super(hotfixPackageList, hotfixMethodList, exceptPackageList, exceptMethodList, isHotfixMethodLevel, isExceptMethodLevel);
    }

    @Override
    protected void insertCode(List<CtClass> box, File jarFile) throws IOException, CannotCompileException {
        ZipOutputStream outStream=new JarOutputStream(new FileOutputStream(jarFile));
        for(CtClass ctClass:box) {
            if(isNeedInsertClass(ctClass.getName())&&!(ctClass.isInterface() || ctClass.getDeclaredMethods().length < 1)) {
                zipFile(transformCode(ctClass.toBytecode(), ctClass.getName().replaceAll("\\.", "/"), String.valueOf(insertMethodCount.get())), outStream, ctClass.getName().replaceAll("\\.", "/") + ".class");
            }else {
                zipFile(ctClass.toBytecode(), outStream, ctClass.getName().replaceAll("\\.", "/") + ".class");

            }
        }
        outStream.close();
    }

   private  class InsertMethodBodyAdapter extends ClassVisitor implements Opcodes {

        public InsertMethodBodyAdapter() {
            super(Opcodes.ASM5);
        }
        ClassWriter classWriter;
        private String className;
        //this maybe change in the future
        private String methodId;
        public InsertMethodBodyAdapter(ClassWriter cw,String className,String methodId) {
            super(Opcodes.ASM5,cw);
            this.classWriter =cw;
            this.className=className;
            this.methodId=methodId;


            classWriter.visitField(Opcodes.ACC_PUBLIC|Opcodes.ACC_STATIC, Constants.INSERT_FIELD_NAME, Type.getDescriptor(ChangeQuickRedirect.class), null, null);
        }


        @Override
        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {

            //
            MethodVisitor mv = super.visitMethod(access, name,
                    desc, signature, exceptions);
            if (!isQualifiedMethod(access,name,desc)) {
//            if (!name.contains("onCreate")) {
                return mv;
            }
            return new MethodBodyInsertor(mv,className,desc,isStatic(access), methodId,name,access);
        }

        private boolean isQualifiedMethod(int access, String name, String desc) {
            //类初始化函数和构造函数过滤
            if(AsmUtils.CLASS_INITIALIZER.equals(name)||AsmUtils.CONSTRUCTOR.equals(name)){
                return false;
            }
            //@warn 这部分代码请重点review一下，判断条件写错会要命
            //这部分代码请重点review一下，判断条件写错会要命
            // synthetic 方法暂时不aop 比如AsyncTask 会生成一些同名 synthetic方法,对synthetic 以及private的方法也插入的代码，主要是针对lambda表达式
            if(((access& Opcodes.ACC_SYNTHETIC) != 0)&&((access & Opcodes.ACC_PRIVATE)==0)){
                return false;
            }
            if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                return false;
            }
            if ((access & Opcodes.ACC_NATIVE) != 0) {
                return false;
            }
            if ((access & Opcodes.ACC_INTERFACE) != 0) {
                return false;
            }

            if ((access & Opcodes.ACC_DEPRECATED) != 0) {
                return false;
            }

            //方法过滤
            if (isExceptMethodLevel && exceptMethodList != null) {
                for (String item : exceptMethodList) {
                    if (name.matches(item)) {
                        return false;
                    }
                }
            }

            if (isHotfixMethodLevel && hotfixMethodList != null) {
                for (String item : hotfixMethodList) {
                    if (name.matches(item)) {
                        return true;
                    }
                }
            }
            return !isHotfixMethodLevel;

        }

        class MethodBodyInsertor extends GeneratorAdapter implements Opcodes {
            private String className;
            private Type[] argsType;
            private Type returnType;
            List<Type> paramsTypeClass=new ArrayList();
            boolean isStatic;
            //目前methodid是int类型的，未来可能会修改为String类型的，这边进行了一次强转
            String methodId;


            public MethodBodyInsertor(MethodVisitor mv,String className, String desc, boolean isStatic,String methodId,String name,int access) {
                super(Opcodes.ASM5, mv, access, name, desc);
                this.className=className;
                this.returnType =Type.getReturnType(desc);
                Type[] argsType = Type.getArgumentTypes(desc);
                for (Type type : argsType) {
                    paramsTypeClass.add(type);
                }
                this.isStatic=isStatic;
                this.methodId =methodId;
            }


            @Override
            public void visitCode() {
                RobustAsmUtils.createInsertCode(this,className,paramsTypeClass, returnType,isStatic,Integer.valueOf(methodId));
            }

        }
        private boolean isStatic(int access){
            return (access & Opcodes.ACC_STATIC) != 0;
        }



    }
    public  byte [] transformCode(byte []b1, String className, String methodId) throws IOException {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        InsertMethodBodyAdapter insertMethodBodyAdapter=new InsertMethodBodyAdapter(cw,className,methodId);
        ClassReader cr = new ClassReader(b1);
        cr.accept(insertMethodBodyAdapter,ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }
    public static void  main(String []args) throws IOException {
    }


}
