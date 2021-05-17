package appeng.container.implementations;

import appeng.api.config.Actionable;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.ITerminalHost;
import appeng.api.storage.channels.IItemStorageChannel;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.container.AEBaseContainer;
import appeng.container.ContainerLocator;
import appeng.container.guisync.GuiSync;
import appeng.core.AELog;
import appeng.core.Api;
import appeng.core.sync.network.NetworkHandler;
import appeng.core.sync.packets.MEInventoryUpdatePacket;
import appeng.me.helpers.PlayerSource;
import appeng.util.Platform;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import tfar.ae2wt.mixin.ContainerAccess;
import tfar.ae2wt.net.TermFactoryConfirm;
import tfar.ae2wt.terminal.ItemWT;
import tfar.ae2wt.util.ContainerHelper;
import tfar.ae2wt.wirelesscraftingterminal.WCTContainer;
import tfar.ae2wt.wirelesscraftingterminal.WCTGuiObject;
import tfar.ae2wt.wpt.WPatternTContainer;
import tfar.ae2wt.wpt.WPTGuiObject;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.inventory.container.ContainerType;
import net.minecraft.inventory.container.IContainerListener;
import net.minecraft.util.Util;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.concurrent.Future;

public class WirelessCraftConfirmContainer extends AEBaseContainer implements CraftingCPUCyclingContainer {

    public static ContainerType<WirelessCraftConfirmContainer> TYPE;

    private static final ContainerHelper<WirelessCraftConfirmContainer, ITerminalHost> helper = new ContainerHelper<>(
            WirelessCraftConfirmContainer::new, SecurityPermissions.CRAFT);

    public static WirelessCraftConfirmContainer openClient(int windowId, PlayerInventory inv) {
        PlayerEntity player = inv.player;
        ItemStack it = inv.player.getHeldItem(Hand.MAIN_HAND);
        ContainerLocator locator = ContainerLocator.forHand(inv.player, Hand.MAIN_HAND);
        WCTGuiObject host = new WCTGuiObject((ItemWT) it.getItem(), it, player, locator.getItemIndex());
        return new WirelessCraftConfirmContainer(windowId, inv, host);
    }

    public static boolean openServer(PlayerEntity player, ContainerLocator locator) {
        ItemStack it = player.inventory.getStackInSlot(locator.getItemIndex());
        WCTGuiObject accessInterface = new WCTGuiObject((ItemWT) it.getItem(), it, player, locator.getItemIndex());

        if(!Platform.checkPermissions(player, accessInterface, SecurityPermissions.CRAFT, true)) return false;


        if (locator.hasItemIndex()) {
            player.openContainer(new TermFactoryConfirm(accessInterface,locator));
        }
        return true;
    }

    private final CraftingCPUCycler cpuCycler;

    private ICraftingCPU selectedCpu;

    private Future<ICraftingJob> job;
    private ICraftingJob result;
    @GuiSync(0)
    public long bytesUsed;
    @GuiSync(3)
    public boolean autoStart = false;
    @GuiSync(4)
    public boolean simulation = true;

    // Indicates whether any CPUs are available
    @GuiSync(6)
    public boolean noCPU = true;

    // Properties of the currently selected crafting CPU, this can be null
    // if no CPUs are available, or if an automatic one is selected
    @GuiSync(1)
    public long cpuBytesAvail;
    @GuiSync(2)
    public int cpuCoProcessors;
    @GuiSync(7)
    public ITextComponent cpuName;

    public WirelessCraftConfirmContainer(int id, PlayerInventory ip, ITerminalHost te) {
        super(TYPE, id, ip, te);
        cpuCycler = new CraftingCPUCycler(this::cpuMatches, this::onCPUSelectionChanged);
        // A player can select no crafting CPU to use a suitable one automatically
        cpuCycler.setAllowNoSelection(true);
    }

    @Override
    public void cycleSelectedCPU(final boolean next) {
        cpuCycler.cycleCpu(next);
    }

    @Override
    public void detectAndSendChanges() {

        if(isClient()) {
            return;
        }

        cpuCycler.detectAndSendChanges(getGrid());

        super.detectAndSendChanges();

        if(getJob() != null && getJob().isDone()) {
            try {
                result = getJob().get();

                if(!result.isSimulation()) {
                    setSimulation(false);
                    if(isAutoStart()) {
                        startJob();
                        return;
                    }
                } else {
                    setSimulation(true);
                }

                try {
                    final MEInventoryUpdatePacket a = new MEInventoryUpdatePacket((byte) 0);
                    final MEInventoryUpdatePacket b = new MEInventoryUpdatePacket((byte) 1);
                    final MEInventoryUpdatePacket c = result.isSimulation() ? new MEInventoryUpdatePacket((byte) 2) : null;

                    final IItemList<IAEItemStack> plan = Api.instance().storage().getStorageChannel(IItemStorageChannel.class).createList();
                    result.populatePlan(plan);

                    setUsedBytes(result.getByteTotal());

                    for(final IAEItemStack out : plan) {
                        IAEItemStack o = out.copy();
                        o.reset();
                        o.setStackSize(out.getStackSize());

                        final IAEItemStack p = out.copy();
                        p.reset();
                        p.setStackSize(out.getCountRequestable());

                        final IStorageGrid sg = getGrid().getCache(IStorageGrid.class);
                        final IMEInventory<IAEItemStack> items = sg.getInventory(Api.instance().storage().getStorageChannel(IItemStorageChannel.class));

                        IAEItemStack m = null;
                        if(c != null && result.isSimulation()) {
                            m = o.copy();
                            o = items.extractItems(o, Actionable.SIMULATE, getActionSource());

                            if(o == null) {
                                o = m.copy();
                                o.setStackSize(0);
                            }

                            m.setStackSize(m.getStackSize() - o.getStackSize());
                        }

                        if(o.getStackSize() > 0) {
                            a.appendItem(o);
                        }

                        if(p.getStackSize() > 0) {
                            b.appendItem(p);
                        }

                        if(c != null && m != null && m.getStackSize() > 0) {
                            c.appendItem(m);
                        }
                    }

                    for(final IContainerListener g : ((ContainerAccess)this).getListeners()) {
                        if(g instanceof PlayerEntity) {
                            NetworkHandler.instance().sendTo(a, (ServerPlayerEntity) g);
                            NetworkHandler.instance().sendTo(b, (ServerPlayerEntity) g);
                            if(c != null) {
                                NetworkHandler.instance().sendTo(c, (ServerPlayerEntity) g);
                            }
                        }
                    }
                } catch(final IOException ignored) {}
            } catch(final Throwable e) {
                getPlayerInv().player.sendMessage(new StringTextComponent("Error: " + e.toString()), Util.DUMMY_UUID);
                AELog.debug(e);
                setValidContainer(false);
                result = null;
            }
            setJob(null);
        }
        verifyPermissions(SecurityPermissions.CRAFT, false);
    }

    private IGrid getGrid() {
        final IActionHost h = ((IActionHost) getTarget());
        return h.getActionableNode().getGrid();
    }

    private boolean cpuMatches(final ICraftingCPU c) {
        return c.getAvailableStorage() >= getUsedBytes() && !c.isBusy();
    }

    public void startJob() {
        ContainerType<?> originalGui = null;

        final IActionHost ah = getActionHost();

        if(ah instanceof WCTGuiObject) {
            originalGui = WCTContainer.TYPE;
        } else if(ah instanceof WPTGuiObject) {
            originalGui = WPatternTContainer.TYPE;
        }

        if(result != null && !isSimulation()) {
            final ICraftingGrid cc = getGrid().getCache(ICraftingGrid.class);
            final ICraftingLink g = cc.submitJob(result, null, selectedCpu, true, getActionSrc());
            setAutoStart(false);
            if(g != null && originalGui != null && getLocator() != null) {
                if(originalGui.equals(WCTContainer.TYPE))
                    WCTContainer.openServer(getPlayerInventory().player, getLocator());
                else if(originalGui.equals(WPatternTContainer.TYPE))
                    WPatternTContainer.openServer(getPlayerInventory().player, getLocator());
            }
        }
    }

    private IActionSource getActionSrc() {
        return new PlayerSource(getPlayerInv().player, (IActionHost) getTarget());
    }

    @Override
    public void removeListener(final IContainerListener c) {
        super.removeListener(c);
        if(getJob() != null) {
            getJob().cancel(true);
            setJob(null);
        }
    }

    @Override
    public void onContainerClosed(final PlayerEntity par1PlayerEntity) {
        super.onContainerClosed(par1PlayerEntity);
        if(getJob() != null) {
            getJob().cancel(true);
            setJob(null);
        }
    }

    private void onCPUSelectionChanged(CraftingCPURecord cpuRecord, boolean cpusAvailable) {
        noCPU = !cpusAvailable;

        if(cpuRecord == null) {
            cpuBytesAvail = 0;
            cpuCoProcessors = 0;
            cpuName = null;
            selectedCpu = null;
        } else {
            cpuBytesAvail = cpuRecord.getSize();
            cpuCoProcessors = cpuRecord.getProcessors();
            cpuName = cpuRecord.getName();
            selectedCpu = cpuRecord.getCpu();
        }
    }

    public World getWorld() {
        return getPlayerInv().player.world;
    }

    public boolean isAutoStart() {
        return autoStart;
    }

    public void setAutoStart(final boolean autoStart) {
        this.autoStart = autoStart;
    }

    public long getUsedBytes() {
        return bytesUsed;
    }

    private void setUsedBytes(final long bytesUsed) {
        this.bytesUsed = bytesUsed;
    }

    public long getCpuAvailableBytes() {
        return cpuBytesAvail;
    }

    public int getCpuCoProcessors() {
        return cpuCoProcessors;
    }

    public ITextComponent getName() {
        return cpuName;
    }

    public boolean hasNoCPU() {
        return noCPU;
    }

    public boolean isSimulation() {
        return simulation;
    }

    private void setSimulation(final boolean simulation) {
        this.simulation = simulation;
    }

    private Future<ICraftingJob> getJob() {
        return job;
    }

    public void setJob(final Future<ICraftingJob> job) {
        this.job = job;
    }
}