package tfar.ae2wt.util;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.storage.ITerminalHost;
import appeng.container.ContainerLocator;
import appeng.container.guisync.GuiSync;
import appeng.container.me.crafting.CraftingCPUContainer;
import appeng.container.me.crafting.CraftingCPUCyclingContainer;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import tfar.ae2wt.cpu.CraftingCPUCycler;
import tfar.ae2wt.cpu.CraftingCPURecord;
import tfar.ae2wt.init.Menus;
import tfar.ae2wt.net.TermFactoryStatus;
import tfar.ae2wt.terminal.AbstractWirelessTerminalItem;
import net.minecraft.util.text.ITextComponent;
import tfar.ae2wt.wirelesscraftingterminal.WCTGuiObject;
import tfar.ae2wt.wpt.WPTGuiObject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class WirelessCraftingStatusContainer extends CraftingCPUContainer implements CraftingCPUCyclingContainer {

    public static WirelessCraftingStatusContainer openClient(int windowId, PlayerInventory inv) {
        PlayerEntity player = inv.player;
        ItemStack it = inv.player.getHeldItem(Hand.MAIN_HAND);
        ContainerLocator locator = ContainerLocator.forHand(inv.player, Hand.MAIN_HAND);
        WCTGuiObject host = new WCTGuiObject((AbstractWirelessTerminalItem) it.getItem(), it, player, locator.getItemIndex());
        return new WirelessCraftingStatusContainer(windowId, inv, host);
    }

    public static void openServer(PlayerEntity player, ContainerLocator locator) {
        ItemStack it = player.inventory.getStackInSlot(locator.getItemIndex());
        WPTGuiObject accessInterface = new WPTGuiObject((AbstractWirelessTerminalItem) it.getItem(), it, player, locator.getItemIndex());

        if (locator.hasItemIndex()) {
            player.openContainer(new TermFactoryStatus(accessInterface,locator));
        }
    }

    public WirelessCraftingStatusContainer(int id, PlayerInventory ip, ITerminalHost terminalHost) {
        super(Menus.WIRELESS_FLUID_TERMINAL, id, ip, terminalHost);
    }

    private final CraftingCPUCycler cpuCycler = new CraftingCPUCycler(this::cpuMatches, this::onCPUSelectionChanged);

    @GuiSync(6)
    public boolean noCPU = true;

    @GuiSync(7)
    public ITextComponent cpuName;


    private static Method m;

    static {
        try {
            m = CraftingCPUContainer.class.getDeclaredMethod("getNetwork");
            m.setAccessible(true);
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void detectAndSendChanges() {
        //package private methof
       // IGrid network = this.getNetwork();
        try {
            IGrid network = (IGrid) m.invoke(this);
            if (isServer() && network != null) {
                cpuCycler.detectAndSendChanges(network);
            }
        } catch (IllegalAccessException|InvocationTargetException e) {
            e.printStackTrace();
        }


        super.detectAndSendChanges();
    }

    private boolean cpuMatches(final ICraftingCPU c) {
        return c.isBusy();
    }

    private void onCPUSelectionChanged(CraftingCPURecord cpuRecord, boolean cpusAvailable) {
        noCPU = !cpusAvailable;
        if (cpuRecord == null) {
            cpuName = null;
            setCPU(null);
        } else {
            cpuName = cpuRecord.getName();
            setCPU(cpuRecord.getCpu());
        }
    }

    @Override
    public void cycleSelectedCPU(boolean forward) {
        cpuCycler.cycleCpu(forward);
    }
}