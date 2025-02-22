package tfar.ae2wt.net;

import appeng.container.AEBaseContainer;
import appeng.container.ContainerLocator;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.container.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraftforge.fml.network.NetworkEvent;
import tfar.ae2wt.wut.WUTItem;
import tfar.ae2wt.wut.WUTHandler;

import java.util.function.Supplier;

public class C2SCycleTerminalPacket {

    public void encode(PacketBuffer buf) {

    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        PlayerEntity player = ctx.get().getSender();

        if (player == null) return;

        ctx.get().enqueueWork(  ()->  {
            final Container screenHandler = player.openContainer;

            if(!(screenHandler instanceof AEBaseContainer)) return;

            final AEBaseContainer container = (AEBaseContainer) screenHandler;
            final ContainerLocator locator = container.getLocator();
            ItemStack item = player.inventory.getStackInSlot(locator.getItemIndex());

            if(!(item.getItem() instanceof WUTItem)) return;
            WUTHandler.cycle(item);

            WUTHandler.open(player, locator);
        });
        ctx.get().setPacketHandled(true);
    }

}
