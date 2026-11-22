// Harbor-Jobs: jobs, advancement, skill trees
plugins {
    id("harbor.paper-module")
}

dependencies {
    // Optional dependency (docs 20 0.3): NPC dialog actions job:change_line and job:reset_skill, advancement cutscene (CutsceneService)
    compileOnly(project(":harbor-npc"))
}
