import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;

/** Inspects real emitted Minecraft vertices, with no game or OpenGL context. */
public final class WorldNoteShapeChecks {
    static int checks;
    public static void main(String[] args)throws Exception {
        Class<?> controller=Class.forName("ru.gora.musicbattle.client.ClientBattleController");
        Method emit;
        try {emit=controller.getDeclaredMethod("emitWorldNoteShape",BufferBuilder.class,Matrix4f.class,
                float.class,float.class,float.class,float.class,float.class,int.class,int.class,int.class);}
        catch(NoSuchMethodException e){throw new AssertionError("World glyphs have no full-lane geometry path; old heads use only 0.15 of 0.21 lane width",e);}
        emit.setAccessible(true);
        Method spawn;
        try {spawn=controller.getDeclaredMethod("worldNoteSpawnY",int.class);}
        catch(NoSuchMethodException e){throw new AssertionError("Enlarged world glyphs still spawn at the bar center and overlap the progress strip",e);}
        spawn.setAccessible(true);
        for(int skin=1;skin<=2;skin++) for(int lane=0;lane<4;lane++) {
            float spawnY=(float)spawn.invoke(null,skin);
            float[] spawned=bounds(emit,new Matrix4f(),.21F,.075F,.04F,skin,lane,spawnY);
            check(spawned[2]>=-.44F,"spawned figure keeps at least 0.03 clearance below progress strip ending at -0.47");
        }
        near((float)spawn.invoke(null,0),-.38F,.00001F,"classic bars retain their original spawn point");
        for(float scaleY:new float[]{-.62F,-.31F,-1.24F}){
            Matrix4f matrix=new Matrix4f().scaling(.62F,scaleY,.62F);
            for(int skin=1;skin<=2;skin++) for(int lane=0;lane<4;lane++) {
                float[] bounds=bounds(emit,matrix,.21F,.075F,.04F,skin,lane);
                float width=bounds[1]-bounds[0],height=bounds[3]-bounds[2];
                check(width>=.21F*.90F*.62F && width<=.21F*.95F*.62F,"world note fills 90-95 percent of available lane width");
                near(width,height,.004F,"world arrows/circles stay square even with unequal pose scales");
                near(bounds[0]+bounds[1],0,.004F,"world glyph stays centered in its lane");
                // Receptors formerly used a different 0.085 radius; non-bar shapes must now match heads.
                float[] receptor=bounds(emit,matrix,.21F,.085F,.045F,skin,lane);
                near(receptor[1]-receptor[0],width,.00001F,"receptor matches full-lane note diameter");
            }
        }
        float[] bars=bounds(emit,new Matrix4f(),.21F,.075F,.04F,0,0);
        near(bars[1]-bars[0],.15F,.00001F,"classic bars retain their established width");
        near(bars[3]-bars[2],.08F,.00001F,"classic bars retain rectangular proportions");
        System.out.println("WORLD_NOTE_SHAPE_CHECKS=PASS checks="+checks);
    }
    static float[] bounds(Method emit,Matrix4f matrix,float available,float halfWidth,float halfHeight,int skin,int lane)throws Exception{
        return bounds(emit,matrix,available,halfWidth,halfHeight,skin,lane,0F);
    }
    static float[] bounds(Method emit,Matrix4f matrix,float available,float halfWidth,float halfHeight,int skin,int lane,float centerY)throws Exception{
        try(ByteBufferBuilder storage=new ByteBufferBuilder(32768)){
            BufferBuilder buffer=new BufferBuilder(storage,VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
            emit.invoke(null,buffer,matrix,0F,centerY,available,halfWidth,halfHeight,skin,lane,0xFFAABBCC);
            try(var mesh=buffer.buildOrThrow()){
                ByteBuffer vertices=mesh.vertexBuffer();
                int stride=mesh.drawState().format().getVertexSize();
                float minX=Float.POSITIVE_INFINITY,maxX=Float.NEGATIVE_INFINITY,minY=minX,maxY=maxX;
                for(int i=0;i<mesh.drawState().vertexCount();i++){
                    float x=vertices.getFloat(i*stride),y=vertices.getFloat(i*stride+4);
                    minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                    if(skin==2 && centerY==0F && x*x+y*y>0.00000001F)
                        near((float)Math.sqrt(x*x+y*y),available*.465F*.62F,.00001F,"circle contour vertices retain equal world-space radius");
                }
                return new float[]{minX,maxX,minY,maxY};
            }
        }
    }
    static void near(float actual,float expected,float epsilon,String label){check(Math.abs(actual-expected)<=epsilon,label+" actual="+actual+" expected="+expected);}
    static void check(boolean condition,String label){checks++;if(!condition)throw new AssertionError(label);}
}
