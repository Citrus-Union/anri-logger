package com.anrilogger.command;

import com.anrilogger.store.*;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.*;
import net.minecraft.resources.Identifier;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import static net.minecraft.ChatFormatting.*;

/** Components are translated on the client; unknown/removed registry entries retain their stored ID. */
public final class LogDisplay {
    private LogDisplay() {}
    public static MutableComponent text(String value, ChatFormatting color) { return Component.literal(value).withStyle(color); }
    public static MutableComponent prefix() { return text("[",DARK_GRAY).append(text("Anri",AQUA).withStyle(BOLD)).append(text("] ",DARK_GRAY)); }
    public static MutableComponent button(String label,String command,boolean run) {
        return text(label,AQUA).withStyle(s -> s.withClickEvent(run?new ClickEvent.RunCommand(command):new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(text((run?"执行 ":"填入 ")+command,YELLOW))));
    }
    public static List<Component> help() {
        return List.of(
            text("━━━━ Anri Logger · 记录查询 ━━━━",AQUA).withStyle(BOLD),
            text("点击蓝色命令即可填入聊天框。",GRAY),
            text("每次玩家操作最多记录 10 次连带方块变化；直接操作和物品存取照常记录。",GRAY),
            button("/al inspect", "/al inspect",false).append(text("  开关检查模式，左/右键点击方块查历史",GRAY)),
            button("/al inspect <x y z>", "/al inspect ",false).append(text("  查询指定坐标",GRAY)),
            button("/al container", "/al container",false).append(text("  查看准星指向容器的物品存取",GREEN)),
            button("/al search <条件>", "/al search ",false).append(text("  按玩家、时间、动作查找",GRAY)),
            button("/al page next", "/al page next",false).append(text("  下一页   ",GRAY)).append(button("/al page prev","/al page prev",false)),
            button("/al show <编号>", "/al show ",false).append(text("  查看完整记录、物品组件和因果链",GRAY)),
            button("/al status", "/al status",false).append(text("  存储状态   ",GRAY)).append(button("/al sessions","/al sessions",false)),
            text("常用过滤：",GOLD).append(text("source:玩家  after:1d  range:32  object:diamond",WHITE)),
            text("动作：",GOLD).append(text("block-break / block-place / block-use / container / item-insert / item-remove",GRAY)),
            button("示例：查看最近一天的容器取放", "/al search action:container after:1d range:32",false),
            text("默认：当前维度周围 16 格 · 仅记录和查询",DARK_GRAY));
    }
    public static MutableComponent objectName(LogEvent event) {
        Identifier id=Identifier.tryParse(event.object());
        if(id!=null) {
            if(event.action().startsWith("item-")) {
                var item=BuiltInRegistries.ITEM.getOptional(id);
                if(item.isPresent())return Component.translatable(item.get().getDescriptionId()).withStyle(WHITE);
            } else {
                var block=BuiltInRegistries.BLOCK.getOptional(id);
                if(block.isPresent())return block.get().getName().withStyle(WHITE);
            }
        }
        return text(event.object(),WHITE);
    }
    public static MutableComponent entry(LogEvent e) {
        ChatFormatting color=switch(e.action()) {
            case "item-insert","block-place" -> GREEN;
            case "item-remove","block-break" -> RED;
            case "explosion" -> DARK_RED;
            case "block-change","entity-trigger" -> GOLD;
            default -> AQUA;
        };
        String verb=switch(e.action()) {
            case "item-insert" -> "向容器存入 "; case "item-remove" -> "从容器取出 ";
            case "block-place" -> "放置 "; case "block-break" -> "破坏 "; case "block-use" -> "使用 ";
            case "container-open" -> "打开 "; case "block-change" -> "引发更新 ";
            case "entity-trigger" -> "触发 "; case "explosion" -> "引发爆炸 "; default -> e.action()+" ";
        };
        String time=DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(e.time()));
        var line=Component.empty().append(button("#"+e.id(),"/al show "+e.id(),true)).append(text("  "+time+"  ",GRAY))
                .append(text(e.playerName()+" ",YELLOW)).append(text(verb,color));
        if(e.action().startsWith("item-"))line.append(text(e.count()+" 个 ",color).withStyle(BOLD));
        line.append(objectName(e)).append(text("  ["+e.x()+", "+e.y()+", "+e.z()+"]",DARK_GRAY));
        String hover="时间："+Instant.ofEpochMilli(e.time())+"\n维度："+e.dimension()+"\n物品/对象 ID："+e.object()
                +"\n数量："+e.count()+"\n玩家 UUID："+e.playerId()+"\n会话："+e.session()+"\n因果链："+e.cause()+"\n详情："+e.detail();
        return line.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(text(hover,GRAY))));
    }
    public static MutableComponent header(Query query,int page,int count) {
        String scope=query.range()==0?query.x()+", "+query.y()+", "+query.z():query.range()<0?"全范围":"周围 "+query.range()+" 格";
        return prefix().append(text(query.action().equals("container")?"容器存取":"历史记录",WHITE).withStyle(BOLD))
                .append(text(" · "+scope+" · "+query.dimension(),GRAY))
                .append(text("  第 "+(page+1)+" 页 / 本页 "+count+" 条",GOLD));
    }
    public static MutableComponent navigation(int page,boolean more) {
        var row=text("  ",GRAY);
        row.append(page>0?button("[上一页]","/al page prev",true):text("[上一页]",DARK_GRAY));
        row.append(text("  ",GRAY)).append(more?button("[下一页]","/al page next",true):text("[已到末尾]",DARK_GRAY));
        return row.append(text("  点击编号看详情 · 悬停看完整信息",GRAY));
    }
}
