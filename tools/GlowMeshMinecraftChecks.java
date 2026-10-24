import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Quaternionf;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import ru.gora.musicbattle.client.GlowMeshExpansion;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.lwjgl.opengl.GL33C.*;

/** Actual ModelPart -> NEW_ENTITY -> shell helper, plus hidden GL pixels; not a Minecraft launch. */
public final class GlowMeshMinecraftChecks {
    private static int checks;
    public static void main(String[] args) {
        for (boolean mirror : new boolean[]{false,true}) {
            for (boolean rotate : new boolean[]{false,true}) {
                PoseStack pose=new PoseStack();
                pose.translate(9,-12,8);
                if(rotate)pose.mulPose(new Quaternionf().rotationXYZ(.3F,.5F,.7F));
                pose.scale(mirror?-2:2,2,2);
                ModelPart part=part();
                part.setPos(0,24,0); // Deliberately remote pivot, the original source of asymmetric glow.
                if(rotate){part.xRot=.7F;part.yRot=.2F;part.zRot=.4F;}
                try(ByteBufferBuilder storage=new ByteBufferBuilder(4096)) {
                    BufferBuilder builder=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
                    part.render(pose,builder,0xF000F0,0,0xFFFFFFFF);
                    try(MeshData mesh=builder.buildOrThrow()) {
                        ByteBuffer b=mesh.vertexBuffer();
                        var f=mesh.drawState().format();
                        int stride=f.getVertexSize(),p=f.getOffset(VertexFormatElement.POSITION),n=f.getOffset(VertexFormatElement.NORMAL);
                        ByteBuffer before=copy(b);
                        GlowMeshExpansion.expand(b,mesh.drawState().vertexCount(),stride,p,n,.1F);
                        double[] deltaSum=new double[3];
                        for(int i=0;i<mesh.drawState().vertexCount();i++) {
                            double length=0;
                            for(int a=0;a<3;a++) {
                                double d=b.getFloat(i*stride+p+a*4)-before.getFloat(i*stride+p+a*4);
                                deltaSum[a]+=d;length+=d*d;
                            }
                            require(Math.abs(Math.sqrt(length)-.1)<.00001,"ModelPart corner displacement is not uniform");
                            for(int j=0;j<i;j++)if(samePosition(before,i,j,stride,p)) {
                                require(samePosition(b,i,j,stride,p),"Transformed hard-edge seam split");
                            }
                        }
                        for(double sum:deltaSum)require(Math.abs(sum)<.0001,"Pivot/rotation/mirror moved shell center");
                        require(part.xScale==1 && part.yScale==1 && part.zScale==1,"Original part scale changed");
                        checks++;
                    }
                }
            }
        }
        pixels();
        System.out.println("Glow Minecraft mesh/GL checks PASS: "+checks+" (in-game integration not covered)");
    }

    private static ModelPart part() {
        ModelPart.Cube cube=new ModelPart.Cube(0,0,-8,-8,-8,16,16,16,0,0,0,false,64,64,EnumSet.allOf(Direction.class));
        return new ModelPart(List.of(cube),Map.of());
    }
    private static boolean samePosition(ByteBuffer b,int i,int j,int stride,int p) {
        for(int a=0;a<3;a++)if(b.getFloat(i*stride+p+a*4)!=b.getFloat(j*stride+p+a*4))return false;
        return true;
    }
    private static ByteBuffer copy(ByteBuffer b) {
        ByteBuffer c=ByteBuffer.allocateDirect(b.remaining()).order(b.order());
        c.put(b.duplicate()).flip();return c;
    }
    private static void pixels() {
        require(GLFW.glfwInit(),"GLFW initialization failed");
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE,GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR,3);
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE,GLFW.GLFW_OPENGL_CORE_PROFILE);
        long window=GLFW.glfwCreateWindow(256,256,"Glow regression (hidden)",0,0);
        require(window!=0,"Hidden GL context failed");
        try {
            GLFW.glfwMakeContextCurrent(window);GL.createCapabilities();
            int vertex=shader(GL_VERTEX_SHADER,"#version 330\nlayout(location=0) in vec3 p;void main(){gl_Position=vec4(p,1);}");
            int fragment=shader(GL_FRAGMENT_SHADER,"#version 330\nuniform vec4 tint;out vec4 c;void main(){c=tint;}");
            int program=glCreateProgram();glAttachShader(program,vertex);glAttachShader(program,fragment);glLinkProgram(program);
            require(glGetProgrami(program,GL_LINK_STATUS)!=0,glGetProgramInfoLog(program));
            glUseProgram(program);int tint=glGetUniformLocation(program,"tint");
            int vao=glGenVertexArrays();glBindVertexArray(vao);
            int vbo=glGenBuffers();glBindBuffer(GL_ARRAY_BUFFER,vbo);
            int ebo=glGenBuffers();glBindBuffer(GL_ELEMENT_ARRAY_BUFFER,ebo);
            int[] indices=new int[36];
            for(int q=0;q<6;q++){int k=q*6,v=q*4;indices[k]=v;indices[k+1]=v+1;indices[k+2]=v+2;indices[k+3]=v+2;indices[k+4]=v+3;indices[k+5]=v;}
            glBufferData(GL_ELEMENT_ARRAY_BUFFER,indices,GL_STATIC_DRAW);
            glViewport(0,0,256,256);glDisable(GL_DEPTH_TEST);glEnable(GL_CULL_FACE);
            try(ByteBufferBuilder storage=new ByteBufferBuilder(4096)) {
                PoseStack pose=new PoseStack();pose.translate(.125,.1875,0);
                BufferBuilder builder=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.NEW_ENTITY);
                part().render(pose,builder,0xF000F0,0,0xFFFFFFFF);
                try(MeshData mesh=builder.buildOrThrow()) {
                    ByteBuffer shell=mesh.vertexBuffer(),normal=copy(shell);
                    var f=mesh.drawState().format();int stride=f.getVertexSize(),p=f.getOffset(VertexFormatElement.POSITION);
                    GlowMeshExpansion.expand(shell,24,stride,p,f.getOffset(VertexFormatElement.NORMAL),.10825318F);
                    glVertexAttribPointer(0,3,GL_FLOAT,false,stride,p);glEnableVertexAttribArray(0);
                    glClearColor(0,0,0,1);glClear(GL_COLOR_BUFFER_BIT);
                    glCullFace(GL_FRONT);glUniform4f(tint,1,0,0,1);glBufferData(GL_ARRAY_BUFFER,shell,GL_STREAM_DRAW);glDrawElements(GL_TRIANGLES,36,GL_UNSIGNED_INT,0);
                    glCullFace(GL_BACK);glUniform4f(tint,0,1,0,1);glBufferData(GL_ARRAY_BUFFER,normal,GL_STREAM_DRAW);glDrawElements(GL_TRIANGLES,36,GL_UNSIGNED_INT,0);
                    ByteBuffer image=ByteBuffer.allocateDirect(256*256*4).order(ByteOrder.nativeOrder());
                    glReadPixels(0,0,256,256,GL_RGBA,GL_UNSIGNED_BYTE,image);
                    int cx=144,cy=152;
                    int left=redRun(image,cx-65,cy,-1,0),right=redRun(image,cx+64,cy,1,0);
                    int bottom=redRun(image,cx,cy-65,0,-1),top=redRun(image,cx,cy+64,0,1);
                    require(left==8 && right==8 && top==8 && bottom==8,"Unequal GL rim: "+left+","+right+","+top+","+bottom);
                    require((image.get((cy*256+cx)*4+1)&255)==255,"Normal geometry did not cover the shell center");
                    checks+=2;
                }
            }
            require(glGetError()==GL_NO_ERROR,"GL error");
            glDeleteBuffers(vbo);glDeleteBuffers(ebo);glDeleteVertexArrays(vao);glDeleteProgram(program);glDeleteShader(vertex);glDeleteShader(fragment);
        } finally {GLFW.glfwDestroyWindow(window);GLFW.glfwTerminate();}
    }
    private static int redRun(ByteBuffer image,int x,int y,int dx,int dy){int n=0;while(x>=0&&x<256&&y>=0&&y<256&&(image.get((y*256+x)*4)&255)==255){n++;x+=dx;y+=dy;}return n;}
    private static int shader(int type,String source){int s=glCreateShader(type);glShaderSource(s,source);glCompileShader(s);require(glGetShaderi(s,GL_COMPILE_STATUS)!=0,glGetShaderInfoLog(s));return s;}
    private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
