package wiki.creeper.harbor.economy.service;

import java.util.Optional;
import org.bukkit.plugin.Plugin;
import wiki.creeper.harbor.common.id.HarborId;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.economy.content.StationDef;
import wiki.creeper.harbor.npc.api.ActionResult;
import wiki.creeper.harbor.npc.api.ArgCheck;
import wiki.creeper.harbor.npc.api.DialogAction;
import wiki.creeper.harbor.npc.api.DialogArgs;
import wiki.creeper.harbor.npc.api.DialogService;

/**
 * NPC 대화 액션 (docs 20 5.2, 7.2 수신 이벤트 'NpcInteractEvent(상점 열기)'). 샘플 대화(fish_market·tackle_keeper·
 * shipwright·peddler)가 이미 {@code shop:open}을 쓴다.
 *
 * <ul>
 *   <li>{@code shop:open [상점 ID]}: 인자 → NPC의 shop 필드 → shops.yml npc가 그 NPC인 상점. 호감도 할인 적용
 *   <li>{@code craft:open <스테이션 ID 또는 workshop · cooking · shipyard>} (추가 제안)
 *   <li>{@code auction:open}, {@code mail:open} (추가 제안)
 * </ul>
 */
public final class DialogActions {

    private DialogActions() {}

    public static void register(
            Plugin plugin,
            DialogService dialogs,
            ShopManager shops,
            CraftManager craft,
            AuctionHouse auction,
            MailManager mail) {
        dialogs.registerAction(
                plugin,
                "shop:open",
                new DialogAction() {
                    @Override
                    public ActionResult run(
                            wiki.creeper.harbor.npc.api.DialogContext ctx, DialogArgs args) {
                        Optional<HarborId> shop =
                                shops.shopOf(ctx.npc(), args.value().orElse(null));
                        if (shop.isEmpty() || !shops.open(ctx.player(), shop.get(), ctx.npc())) {
                            return ActionResult.failMessage("economy.shop.none");
                        }
                        return ActionResult.HANDOFF;
                    }

                    @Override
                    public void validate(DialogArgs args, ArgCheck check) {
                        args.value()
                                .ifPresent(
                                        raw -> {
                                            HarborId id = HarborId.tryParse(raw).orElse(null);
                                            if (id == null
                                                    || !check.snapshot()
                                                            .has(ShopDef.TYPE.name(), id)) {
                                                check.error("없는 상점입니다: " + raw);
                                            }
                                        });
                    }
                });
        dialogs.registerAction(
                plugin,
                "craft:open",
                new DialogAction() {
                    @Override
                    public ActionResult run(
                            wiki.creeper.harbor.npc.api.DialogContext ctx, DialogArgs args) {
                        String target = args.value().orElse("workshop");
                        boolean ok =
                                craft.openKind(ctx.player(), target)
                                        || HarborId.tryParse(target)
                                                .map(id -> craft.openStation(ctx.player(), id))
                                                .orElse(false);
                        return ok
                                ? ActionResult.HANDOFF
                                : ActionResult.failMessage("economy.craft.none");
                    }

                    @Override
                    public void validate(DialogArgs args, ArgCheck check) {
                        args.value()
                                .ifPresent(
                                        raw -> {
                                            boolean kind =
                                                    raw.equals("workshop")
                                                            || raw.equals("cooking")
                                                            || raw.equals("shipyard");
                                            boolean station =
                                                    HarborId.tryParse(raw)
                                                            .map(
                                                                    id ->
                                                                            check.snapshot()
                                                                                    .has(
                                                                                            StationDef
                                                                                                    .TYPE
                                                                                                    .name(),
                                                                                            id))
                                                            .orElse(false);
                                            if (!kind && !station) {
                                                check.error("없는 제작 장소입니다: " + raw);
                                            }
                                        });
                    }
                });
        dialogs.registerAction(
                plugin,
                "auction:open",
                (ctx, args) -> {
                    auction.open(ctx.player(), "all", null, false, 0);
                    return ActionResult.HANDOFF;
                });
        dialogs.registerAction(
                plugin,
                "mail:open",
                (ctx, args) -> {
                    mail.open(ctx.player());
                    return ActionResult.HANDOFF;
                });
    }
}
