package phys_engine;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;


import java.awt.event.KeyEvent;

public class Physengine extends JPanel implements ActionListener, MouseListener, MouseMotionListener, KeyListener {


    static class Vec2 {
        double x, y;
        Vec2(double x, double y){ this.x=x; this.y=y; }
        Vec2(){ this(0,0); }
        Vec2 set(double x,double y){ this.x=x; this.y=y; return this; }
        Vec2 cpy(){ return new Vec2(x,y); }
        Vec2 add(Vec2 v){ x+=v.x; y+=v.y; return this; }
        Vec2 sub(Vec2 v){ x-=v.x; y-=v.y; return this; }
        Vec2 scl(double s){ x*=s; y*=s; return this; }
        double dot(Vec2 v){ return x*v.x + y*v.y; }
        double len(){ return Math.sqrt(x*x + y*y); }
        Vec2 nor(){ double l=len(); if(l>1e-9){ x/=l; y/=l; } return this; }
        static Vec2 add(Vec2 a, Vec2 b){ return new Vec2(a.x+b.x, a.y+b.y); }
        static Vec2 sub(Vec2 a, Vec2 b){ return new Vec2(a.x-b.x, a.y-b.y); }
        static Vec2 scl(Vec2 a, double s){ return new Vec2(a.x*s, a.y*s); }
        Vec2 norCpy(){ double l=len(); return l>1e-9? new Vec2(x/l, y/l) : new Vec2(1,0); }
    }


    static class Body {
        Vec2 pos = new Vec2();
        Vec2 vel = new Vec2();
        Vec2 force = new Vec2();
        double radius;
        double invMass;
        double restitution = 0.3;
        double friction = 0.2;
        Color color = Color.BLUE;
        boolean isWater=false;
        boolean isMagnet=false;
        boolean magnetTypeWater=false; 
        double magnetStrength=5000;
        double magnetRadius=100;

        Body(double x, double y, double radius, double mass){
            pos.set(x,y);
            this.radius = radius;
            if(mass<=0) invMass=0; else invMass=1.0/mass;
        }
        boolean isStatic(){ return invMass==0; }
        void applyForce(Vec2 f){ force.add(f); }
        void integrate(double dt){
            if(isStatic() && !isMagnet) return;
            vel.add(Vec2.scl(force, invMass*dt));
            pos.add(Vec2.scl(vel, dt));
            force.set(0,0);
        }
    }


    static class Contact {
        Body a,b;
        Vec2 normal;
        double penetration;
        Contact(Body a, Body b, Vec2 n, double p){ this.a=a; this.b=b; normal=n; penetration=p; }
    }


    static class ScriptBox {
        int x, y, w=60, h=30;
        String type;
        boolean enabled=false, repeated=false, activated=false;
        SpawnType spawnType=SpawnType.DYNAMIC;
        boolean spawnAtCursor=false, spawnAtBox=false;
        ScriptBox(int x,int y,String type){ this.x=x; this.y=y; this.type=type; }
        boolean contains(int mx,int my){ return mx>=x && mx<=x+w && my>=y && my<=y+h; }
        void draw(Graphics2D g){
            g.setColor(enabled?Color.GREEN:Color.RED);
            g.fillRect(x,y,w,h);
            g.setColor(Color.BLACK);
            g.drawRect(x,y,w,h);
            g.drawString(type,x+5,y+h/2+5);
        }
    }


    public interface ModScript {
        void init(Physengine engine);
        void update(double dt);
    }


    List<Body> bodies=new ArrayList<>();
    List<Contact> contacts=new ArrayList<>();
    List<ScriptBox> scriptBoxes=new ArrayList<>();
    List<ModScript> modScripts=new ArrayList<>();

    double gravity=980;
    double defaultGravity=980;
    Timer timer;
    final double TIME_STEP=1.0/60.0;
    Body selected=null;
    int mouseX, mouseY;

    enum SpawnMode { DYNAMIC, STATIC, BIG_STATIC, WATER, WATER_MAGNET, OBJECT_MAGNET }
    SpawnMode currentMode=SpawnMode.DYNAMIC;

    enum SpawnType { DYNAMIC, STATIC, BIG_STATIC, WATER, WATER_MAGNET, OBJECT_MAGNET }
    List<SpawnType> spawnableTypes=new ArrayList<>(List.of(SpawnType.values()));

    boolean showCollisions=false;


    public Physengine(){
        setPreferredSize(new Dimension(900,600));
        setBackground(Color.WHITE);
        addMouseListener(this);
        addMouseMotionListener(this);
        setFocusable(true);
        addKeyListener(this);

        timer=new Timer((int)(TIME_STEP*1000), this);
        timer.start();


        File modsDir = new File("mods/");
        if(!modsDir.exists()) modsDir.mkdirs();
    }


    @Override
    public void actionPerformed(ActionEvent e){
        step(TIME_STEP);


        for(ModScript ms:modScripts) ms.update(TIME_STEP);


        for(ScriptBox sb:scriptBoxes){
            if(sb.enabled && sb.type.equals("spawnscr")){
                Vec2 spawnPos = new Vec2(mouseX, mouseY);
                if(sb.spawnAtBox) spawnPos.set(sb.x+sb.w/2, sb.y+sb.h/2);
                if(sb.repeated) spawnSelected(sb.spawnType,(int)spawnPos.x,(int)spawnPos.y);
                else if(sb.activated) spawnSelected(sb.spawnType,(int)spawnPos.x,(int)spawnPos.y);
            }
        }


        boolean anyZeroGravity=false;
        for(ScriptBox sb:scriptBoxes){
            if(sb.enabled && sb.type.equals("enviorsc")){
                if(sb.activated) anyZeroGravity=true;
            }
        }
        gravity=anyZeroGravity? 0 : defaultGravity;


        for(Body magnet:bodies){
            if(magnet.isMagnet){
                for(Body b:bodies){
                    if(b==magnet) continue;
                    if(magnet.magnetTypeWater && !b.isWater) continue;
                    if(!magnet.magnetTypeWater && b.isWater) continue;
                    Vec2 dir=Vec2.sub(magnet.pos,b.pos);
                    double dist=Math.max(dir.len(),5);
                    Vec2 force=dir.norCpy().scl(magnet.magnetStrength/dist);
                    b.applyForce(force);
                }
            }
        }

        repaint();
    }


    void step(double dt){
        for(Body b:bodies){
            if(!b.isStatic() && !b.isMagnet) b.applyForce(new Vec2(0,gravity));
        }
        for(Body b:bodies) b.integrate(dt);

        contacts.clear();
        int n=bodies.size();
        for(int i=0;i<n;i++)
            for(int j=i+1;j<n;j++){
                Body A=bodies.get(i), B=bodies.get(j);
                Contact c=collideCircleCircle(A,B);
                if(c!=null) contacts.add(c);
            }

        for(Contact c:contacts) resolveContact(c);
        for(Contact c:contacts) positionalCorrection(c);

        for(Body b:bodies){
            if(b.isStatic() || b.isMagnet) continue;
            double r=b.radius;
            if(b.pos.x-r<0){ b.pos.x=r; b.vel.x*=-0.5; }
            if(b.pos.x+r>getWidth()){ b.pos.x=getWidth()-r; b.vel.x*=-0.5; }
            if(b.pos.y-r<0){ b.pos.y=r; b.vel.y*=-0.5; }
            if(b.pos.y+r>getHeight()){ b.pos.y=getHeight()-r; b.vel.y*=-0.5; }
        }
    }

    Contact collideCircleCircle(Body a, Body b){
        double dx=b.pos.x-a.pos.x, dy=b.pos.y-a.pos.y;
        double dist=Math.sqrt(dx*dx+dy*dy), r=a.radius+b.radius;
        if(dist>=r) return null;
        Vec2 n=dist<1e-6? new Vec2(1,0) : new Vec2(dx/dist,dy/dist);
        double penetration=r-dist;
        return new Contact(a,b,n,penetration);
    }

    void resolveContact(Contact c){
        Body A=c.a,B=c.b;
        Vec2 rv=Vec2.sub(B.vel,A.vel);
        double velAlongNormal=rv.dot(c.normal);
        if(velAlongNormal>0) return;

        double e=Math.min(A.restitution,B.restitution);
        double j=-(1+e)*velAlongNormal;
        double invMassSum=A.invMass+B.invMass;
        if(invMassSum==0) return;
        j/=invMassSum;

        Vec2 impulse=Vec2.scl(c.normal,j);
        if(!A.isStatic()) A.vel.sub(Vec2.scl(impulse,A.invMass));
        if(!B.isStatic()) B.vel.add(Vec2.scl(impulse,B.invMass));

        rv=Vec2.sub(B.vel,A.vel);
        Vec2 tangent=new Vec2(rv.x-c.normal.x*rv.dot(c.normal), rv.y-c.normal.y*rv.dot(c.normal));
        double tlen=tangent.len();
        if(tlen>1e-6){
            tangent.scl(1.0/tlen);
            double jt=-rv.dot(tangent)/invMassSum;
            double mu=Math.sqrt(A.friction*B.friction);
            Vec2 frictionImpulse=Math.abs(jt)<j*mu? Vec2.scl(tangent,jt) : Vec2.scl(tangent,-j*mu);
            if(!A.isStatic()) A.vel.sub(Vec2.scl(frictionImpulse,A.invMass));
            if(!B.isStatic()) B.vel.add(Vec2.scl(frictionImpulse,B.invMass));
        }
    }

    void positionalCorrection(Contact c){
        Body A=c.a,B=c.b;
        double invMassSum=A.invMass+B.invMass;
        if(invMassSum==0) return;
        double percent=0.8, slop=0.01, maxCorrection=5;
        double corrMag=Math.min(Math.max(c.penetration-slop,0)/invMassSum*percent,maxCorrection);
        Vec2 corr=Vec2.scl(c.normal,corrMag);
        if(!A.isStatic()) A.pos.sub(Vec2.scl(corr,A.invMass));
        if(!B.isStatic()) B.pos.add(Vec2.scl(corr,B.invMass));
    }

    @Override
    protected void paintComponent(Graphics g){
        super.paintComponent(g);
        Graphics2D g2=(Graphics2D) g;
        for(Body b:bodies){
            g2.setColor(b.color);
            int x=(int)(b.pos.x-b.radius), y=(int)(b.pos.y-b.radius), d=(int)(b.radius*2);
            g2.fillOval(x,y,d,d);
            g2.setColor(Color.BLACK);
            g2.drawOval(x,y,d,d);
            if(b.isMagnet){
                g2.setColor(b.magnetTypeWater?Color.BLUE:Color.RED);
                g2.drawOval((int)(b.pos.x-b.magnetRadius),(int)(b.pos.y-b.magnetRadius),(int)(b.magnetRadius*2),(int)(b.magnetRadius*2));
            }
        }
        for(ScriptBox sb:scriptBoxes) sb.draw(g2);

        if(showCollisions){
            g2.setColor(Color.RED);
            for(Contact c:contacts){
                Vec2 p=Vec2.add(c.a.pos,Vec2.scl(c.normal,c.a.radius));
                g2.drawLine((int)p.x,(int)p.y,(int)(p.x+c.normal.x*10),(int)(p.y+c.normal.y*10));
            }
        }

        if(selected!=null){
            g2.setColor(Color.MAGENTA);
            g2.drawOval((int)(selected.pos.x-selected.radius),(int)(selected.pos.y-selected.radius),
                        (int)(selected.radius*2),(int)(selected.radius*2));
        }

        g2.setColor(currentMode==SpawnMode.DYNAMIC?Color.GREEN:
                    currentMode==SpawnMode.STATIC?Color.GRAY:
                    currentMode==SpawnMode.BIG_STATIC?Color.DARK_GRAY:
                    currentMode==SpawnMode.WATER?new Color(0,100,255,180):
                    currentMode==SpawnMode.WATER_MAGNET?Color.BLUE:Color.RED);
        int r=(currentMode==SpawnMode.BIG_STATIC?60:20);
        g2.drawOval(mouseX-r,mouseY-r,r*2,r*2);
    }


    @Override
    public void mousePressed(MouseEvent e){
        int x=e.getX(), y=e.getY();
        for(ScriptBox sb:scriptBoxes){
            if(sb.contains(x,y)){
                if(SwingUtilities.isRightMouseButton(e)) sb.enabled=!sb.enabled;
                else {
                    if(sb.type.equals("spawnscr")) openSpawnScriptWindow(sb);
                    if(sb.type.equals("enviorsc")) openEnvironmentScriptsWindow(sb);
                }
                return;
            }
        }
        selected=findBodyAt(x,y);
        if(selected!=null){ selected.color=Color.ORANGE; selected.vel.set(0,0); }
        else spawnSelected(convertModeToType(currentMode),x,y);
    }
    @Override public void mouseReleased(MouseEvent e){ if(selected!=null) selected.color=Color.BLUE; selected=null; }
    @Override public void mouseDragged(MouseEvent e){ if(selected!=null){ selected.pos.set(e.getX(),e.getY()); selected.vel.set(0,0); } }
    @Override public void mouseMoved(MouseEvent e){ mouseX=e.getX(); mouseY=e.getY(); }
    @Override public void mouseClicked(MouseEvent e){}
    @Override public void mouseEntered(MouseEvent e){}
    @Override public void mouseExited(MouseEvent e){}

    Body findBodyAt(int x,int y){
        for(int i=bodies.size()-1;i>=0;i--){
            Body b=bodies.get(i);
            double dx=x-b.pos.x, dy=y-b.pos.y;
            if(dx*dx+dy*dy<=b.radius*b.radius) return b;
        }
        return null;
    }

    void spawnSelected(SpawnType type,int x,int y){
        switch(type){
            case DYNAMIC: bodies.add(new Body(x,y,20,1.0)); break;
            case STATIC: Body s=new Body(x,y,20,0); s.color=Color.GRAY; bodies.add(s); break;
            case BIG_STATIC: Body big=new Body(x,y,60,0); big.color=Color.DARK_GRAY; bodies.add(big); break;
            case WATER:
                for(int i=0;i<30;i++){
                    double ox=(Math.random()-0.5)*40, oy=(Math.random()-0.5)*40;
                    Body w=new Body(x+ox,y+oy,6,0.1);
                    w.color=new Color(0,100,255,180);
                    w.isWater=true;
                    w.friction=0.0; w.restitution=0.1;
                    bodies.add(w);
                }
                break;
            case WATER_MAGNET:
                Body wm=new Body(x,y,30,0);
                wm.isMagnet=true; wm.magnetTypeWater=true; wm.color=Color.BLUE;
                bodies.add(wm);
                break;
            case OBJECT_MAGNET:
                Body om=new Body(x,y,30,0);
                om.isMagnet=true; om.magnetTypeWater=false; om.color=Color.RED;
                bodies.add(om);
                break;
        }
    }

    SpawnType convertModeToType(SpawnMode m){
        switch(m){
            case DYNAMIC: return SpawnType.DYNAMIC;
            case STATIC: return SpawnType.STATIC;
            case BIG_STATIC: return SpawnType.BIG_STATIC;
            case WATER: return SpawnType.WATER;
            case WATER_MAGNET: return SpawnType.WATER_MAGNET;
            case OBJECT_MAGNET: return SpawnType.OBJECT_MAGNET;
        }
        return SpawnType.DYNAMIC;
    }

    void openSpawnScriptWindow(ScriptBox sb){
        JFrame scriptFrame=new JFrame("Spawn Script Properties");
        scriptFrame.setSize(400,250); scriptFrame.setLayout(new FlowLayout());
        JComboBox<SpawnType> objectSelector=new JComboBox<>(spawnableTypes.toArray(new SpawnType[0]));
        objectSelector.setSelectedItem(sb.spawnType);
        JCheckBox repeated=new JCheckBox("Repeated"); repeated.setSelected(sb.repeated);
        JCheckBox activated=new JCheckBox("Activated"); activated.setSelected(sb.activated);
        JCheckBox atCursor=new JCheckBox("Spawn at Cursor"); atCursor.setSelected(sb.spawnAtCursor);
        JCheckBox atBox=new JCheckBox("Spawn at Script Box"); atBox.setSelected(sb.spawnAtBox);
        JButton applyButton=new JButton("Apply");
        applyButton.addActionListener(e->{ 
            sb.spawnType=(SpawnType)objectSelector.getSelectedItem(); 
            sb.repeated=repeated.isSelected(); 
            sb.activated=activated.isSelected(); 
            sb.spawnAtCursor=atCursor.isSelected(); 
            sb.spawnAtBox=atBox.isSelected(); 
            scriptFrame.dispose(); 
        });
        scriptFrame.add(objectSelector); scriptFrame.add(repeated); scriptFrame.add(activated); scriptFrame.add(atCursor); scriptFrame.add(atBox); scriptFrame.add(applyButton);
        scriptFrame.setVisible(true);
    }

    void openEnvironmentScriptsWindow(ScriptBox sb){
        JFrame envFrame=new JFrame("Environment Scripts"); envFrame.setSize(300,200); envFrame.setLayout(new FlowLayout());
        JButton zeroGravityBtn=new JButton("Zero Gravity"); zeroGravityBtn.addActionListener(e->{ if(sb.enabled) sb.activated=true; });
        JButton resetGravityBtn=new JButton("Reset Gravity"); resetGravityBtn.addActionListener(e->{ if(sb.enabled) sb.activated=false; });
        JButton earthquakeBtn=new JButton("Earthquake"); earthquakeBtn.addActionListener(e->{ if(sb.enabled) applyEarthquake(); });
        envFrame.add(zeroGravityBtn); envFrame.add(resetGravityBtn); envFrame.add(earthquakeBtn);
        envFrame.setVisible(true);
    }

    void applyEarthquake(){ for(Body b:bodies){ if(!b.isStatic() && !b.isMagnet){ b.vel.x+=(Math.random()-0.5)*500; b.vel.y+=(Math.random()-0.5)*500; } } }

    @Override
    public void keyPressed(KeyEvent e){
        if(e.getKeyCode()==KeyEvent.VK_Z){
            bodies.clear();
            scriptBoxes.clear();
        }
    }
    @Override public void keyReleased(KeyEvent e){}
    @Override public void keyTyped(KeyEvent e){}

    public static void main(String[] args){
        SwingUtilities.invokeLater(()->{
            JFrame frame=new JFrame("Hamengine");
            Physengine engine=new Physengine();
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(engine);
            frame.pack();
            frame.setLocationRelativeTo(null);

            JMenuBar menuBar=new JMenuBar();
            JMenu addMenu=new JMenu("Add Object");
            JMenuItem dynamic=new JMenuItem("Dynamic Circle");
            JMenuItem stat=new JMenuItem("Static Circle");
            JMenuItem bigStatic=new JMenuItem("Big Static Circle");
            JMenuItem water=new JMenuItem("Water");
            JMenuItem waterMagnet=new JMenuItem("Water Magnet");
            JMenuItem objectMagnet=new JMenuItem("Object Magnet");
            dynamic.addActionListener(e->engine.currentMode=SpawnMode.DYNAMIC);
            stat.addActionListener(e->engine.currentMode=SpawnMode.STATIC);
            bigStatic.addActionListener(e->engine.currentMode=SpawnMode.BIG_STATIC);
            water.addActionListener(e->engine.currentMode=SpawnMode.WATER);
            waterMagnet.addActionListener(e->engine.currentMode=SpawnMode.WATER_MAGNET);
            objectMagnet.addActionListener(e->engine.currentMode=SpawnMode.OBJECT_MAGNET);
            addMenu.add(dynamic); addMenu.add(stat); addMenu.add(bigStatic); addMenu.add(water); addMenu.add(waterMagnet); addMenu.add(objectMagnet);

            JMenu settingsMenu=new JMenu("Settings");
            JCheckBoxMenuItem showColItem=new JCheckBoxMenuItem("Show Collisions");
            showColItem.setSelected(false); showColItem.addActionListener(e->engine.showCollisions=showColItem.isSelected());
            settingsMenu.add(showColItem);

            JMenu editablesMenu=new JMenu("Editables");
            JMenuItem spawnScriptItem=new JMenuItem("Spawn Script");
            spawnScriptItem.addActionListener(e->engine.scriptBoxes.add(new ScriptBox(100,100,"spawnscr")));
            JMenuItem envScriptsItem=new JMenuItem("Environment Script");
            envScriptsItem.addActionListener(e->engine.scriptBoxes.add(new ScriptBox(200,100,"enviorsc")));
            editablesMenu.add(spawnScriptItem); editablesMenu.add(envScriptsItem);

            menuBar.add(addMenu); menuBar.add(settingsMenu); menuBar.add(editablesMenu);
            frame.setJMenuBar(menuBar);
            frame.setVisible(true);


            engine.loadMods();
        });
    }


    void loadMods(){
        try {
            File modsDir = new File("mods/");
            if(!modsDir.exists()) return;
            File[] files = modsDir.listFiles((dir, name)->name.endsWith(".class"));
            if(files==null) return;

            URL[] urls = { modsDir.toURI().toURL() };
            URLClassLoader classLoader = URLClassLoader.newInstance(urls);

            for(File f: files){
                String className = f.getName().replace(".class","");
                Class<?> cls = classLoader.loadClass(className); // FIXED: loadClass
                if(ModScript.class.isAssignableFrom(cls)){
                    ModScript script = (ModScript) cls.getDeclaredConstructor().newInstance();
                    script.init(this);
                    modScripts.add(script);
                }
            }
        } catch(Exception ex){ ex.printStackTrace(); }
    }
}
