package wiki.creeper.harbor.tools;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import wiki.creeper.harbor.collection.content.CollectionContent;
import wiki.creeper.harbor.common.content.ContentLoader;
import wiki.creeper.harbor.common.content.ContentSnapshot;
import wiki.creeper.harbor.common.content.type.Balance;
import wiki.creeper.harbor.common.content.type.CoreContent;
import wiki.creeper.harbor.common.util.Result;
import wiki.creeper.harbor.common.util.ValidationErrors;
import wiki.creeper.harbor.economy.content.EconomyContent;
import wiki.creeper.harbor.economy.content.ShopDef;
import wiki.creeper.harbor.jobs.content.JobLineDef;
import wiki.creeper.harbor.jobs.content.JobsContent;

/**
 * 콘텐츠 검증 CLI. 서버와 같은 로더로 content/ YAML을 검사해서, 디자이너가 올린 YAML의 오류를 서버에 넣기 전에 CI에서 거른다.
 *
 * <pre>
 * harbor-tools validate &lt;콘텐츠 폴더&gt;
 * </pre>
 *
 * 서버 API가 필요한 검사(바닐라 아이템 ID 등)는 서버에서만 한다. 모듈을 추가하면 그 모듈의 XxxContent도 여기에 등록한다.
 */
public final class HarborTools {

    private HarborTools() {}

    public static void main(String[] args) {
        PrintStream out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length != 2 || !args[0].equals("validate")) {
            out.println("사용법: harbor-tools validate <콘텐츠 폴더>");
            System.exit(2);
            return;
        }
        ContentLoader loader = new ContentLoader();
        CoreContent.register(loader);
        JobsContent.TYPES.forEach(loader::register);
        CollectionContent.TYPES.forEach(loader::register);
        EconomyContent.TYPES.forEach(loader::register);
        loader.addCheck(ShopDef.TYPE, ShopDef.PRICE_CHECK);
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
}
