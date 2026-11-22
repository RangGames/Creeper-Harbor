package wiki.creeper.harbor.tools;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import wiki.creeper.harbor.boss.content.BossContent;
import wiki.creeper.harbor.boss.content.BossDef;
import wiki.creeper.harbor.collection.content.CollectionContent;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.economy.content.EconomyContent;
import wiki.creeper.harbor.economy.content.RecipeDef;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.fishing.content.FishingContent;
import wiki.creeper.harbor.jobs.content.JobLineDef;
import wiki.creeper.harbor.jobs.content.JobsContent;
import wiki.creeper.harbor.npc.content.CutsceneDef;
import wiki.creeper.harbor.npc.content.DialogDef;
import wiki.creeper.harbor.npc.content.NpcContent;
import wiki.creeper.harbor.npc.content.NpcDef;
import wiki.creeper.harbor.quest.content.QuestContent;
import wiki.creeper.harbor.season.content.SeasonContent;
import wiki.creeper.harbor.shop.content.ShopContent;
import wiki.creeper.harbor.wildlife.content.WildSpecies;
import wiki.creeper.harbor.wildlife.content.WildlifeContent;
import wiki.creeper.harbor.world.content.WorldContent;

/**
 * Content validation CLI. Validates content/ YAML with the same loader as the server, so CI catches
 * errors in designer-submitted YAML before it reaches the server.
 *
 * <pre>
 * harbor-tools validate &lt;content folder&gt;
 * harbor-tools pack-index &lt;resource pack folder or zip&gt; &lt;content folder&gt;   (generates resource_pack.yml)
 * harbor-tools rig-import &lt;rig JSON folder&gt; &lt;content folder&gt; [sample interval ticks]   (generates rigs/fish/*.yml)
 * </pre>
 *
 * Checks that need the server API (vanilla item IDs, etc.) run only on the server. When adding a
 * module, register its XxxContent here too.
 */
public final class HarborTools {

    private HarborTools() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if ((args.length == 3 || args.length == 4) && args[0].equals("rig-import")) {
            rigImport(args, out);
            return;
        }
        if (args.length == 3 && args[0].equals("pack-index")) {
            packIndex(Path.of(args[1]), Path.of(args[2]), out);
            return;
        }
        if (args.length != 2 || !args[0].equals("validate")) {
            out.println("사용법: harbor-tools validate <콘텐츠 폴더>");
            out.println("        harbor-tools pack-index <리소스팩 폴더 또는 zip> <콘텐츠 폴더>");
            out.println("        harbor-tools rig-import <리그 JSON 폴더> <콘텐츠 폴더> [샘플 간격 틱]");
            System.exit(2);
            return;
        }
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        JobsContent.TYPES.forEach(loader::register);
        FishingContent.TYPES.forEach(loader::register);
        CollectionContent.TYPES.forEach(loader::register);
        EconomyContent.TYPES.forEach(loader::register);
        SeasonContent.TYPES.forEach(loader::register);
        NpcContent.TYPES.forEach(loader::register);
        QuestContent.register(loader);
        ShopContent.TYPES.forEach(loader::register);
        BossContent.TYPES.forEach(loader::register);
        WorldContent.TYPES.forEach(loader::register);
        WildlifeContent.TYPES.forEach(loader::register);
        loader.addCheck(WildSpecies.TYPE, WildlifeContent.SPECIAL_CHECK);
        loader.addCheck(WildSpecies.TYPE, WildlifeContent.FISH_UNIQUE);
        loader.addCheck(BossDef.TYPE, BossContent.RIG_CHECK);
        loader.addCheck(BossDef.TYPE, BossContent.FISH_CHECK);
        ShopContent.registerChecks(loader::addCheck);
        loader.addCheck(DialogDef.TYPE, NpcContent.DIALOG_MESSAGES);
        loader.addCheck(CutsceneDef.TYPE, NpcContent.CUTSCENE_MESSAGES);
        loader.addCheck(NpcDef.TYPE, NpcContent.NPC_MESSAGES);
        loader.addCheck(ShopDef.TYPE, ShopDef.PRICE_CHECK);
        loader.addCheck(RecipeDef.TYPE, EconomyContent.RECIPE_CHECK);
        loader.addCheck(Balance.TYPE, JobsContent.CURVE_CHECK);
        loader.addCheck(JobLineDef.TYPE, JobsContent.CAPSTONE_CHECK);
        Result<ContentSnapshot, ValidationErrors> result = loader.load(Path.of(args[1]));
        switch (result) {
            case Result.Ok<ContentSnapshot, ValidationErrors>(ContentSnapshot snapshot) -> {
                out.println("콘텐츠 검사 통과: " + snapshot.counts());
                System.exit(0);
            }
            case Result.Err<ContentSnapshot, ValidationErrors>(ValidationErrors errors) -> {
                out.println("콘텐츠 오류 " + errors.size() + "건:");
                out.println(errors.format());
                System.exit(1);
            }
        }
    }

    /**
     * Resource pack fish rig JSON → content/rigs/fish/*.yml. Without an interval, uses balance.yml
     * rig.fps (20 / fps ticks).
     */
    private static void rigImport(String[] args, PrintStream out) {
        Path contentDir = Path.of(args[2]);
        try {
            int step =
                    args.length == 4
                            ? Integer.parseInt(args[3])
                            : 20 / Math.max(1, rigFps(contentDir));
            var converted = RigImporter.run(Path.of(args[1]), contentDir, step);
            long bytes = 0;
            for (var c : converted) {
                bytes += c.yaml().getBytes(StandardCharsets.UTF_8).length;
                if (!c.events().isEmpty()) {
                    out.println("  " + c.name() + " 이벤트: " + c.events());
                }
            }
            out.println(
                    "rigs/fish: 리그 "
                            + converted.size()
                            + "개 ("
                            + bytes / 1024
                            + " KiB, 샘플 간격 "
                            + step
                            + "틱)");
            System.exit(0);
        } catch (IOException | NumberFormatException e) {
            out.println("리그를 변환하지 못했습니다: " + e.getMessage());
            System.exit(1);
        }
    }

    /** balance.yml rig.fps (10 if absent). */
    private static int rigFps(Path contentDir) throws IOException {
        Path balance = contentDir.resolve("balance.yml");
        if (!Files.isRegularFile(balance)) {
            return 10;
        }
        Object root =
                new org.yaml.snakeyaml.Yaml()
                        .load(Files.readString(balance, StandardCharsets.UTF_8));
        if (root instanceof java.util.Map<?, ?> map
                && map.get("rig") instanceof java.util.Map<?, ?> rig
                && rig.get("fps") instanceof Number fps
                && fps.intValue() > 0
                && 20 % fps.intValue() == 0) {
            return fps.intValue();
        }
        return 10;
    }

    private static void packIndex(Path pack, Path contentDir, PrintStream out) {
        try {
            PackIndexer.Index index = PackIndexer.read(pack);
            Path file = contentDir.resolve("resource_pack.yml");
            Path name = pack.toAbsolutePath().getFileName();
            Files.writeString(
                    file,
                    PackIndexer.yaml(index, name == null ? "?" : name.toString()),
                    StandardCharsets.UTF_8);
            out.println(
                    "resource_pack.yml: 모델 "
                            + index.models().size()
                            + "개, 소리 "
                            + index.sounds().size()
                            + "개");
            System.exit(0);
        } catch (IOException e) {
            out.println("리소스팩을 읽지 못했습니다: " + e.getMessage());
            System.exit(1);
        }
    }
}
