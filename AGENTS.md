## HelloAI Agent Instructions

This repository contains project-level instructions for any AI agent that reads `AGENTS.md` before making changes.

These instructions apply when modifying backend code, frontend code, scripts, SQL, configuration, tests, or project documentation tied to code facts.

### Required Reading Before Any Code Change

Before editing code in this repository, read these documents first:

1. `doc/HelloAI_项目基线文档.md`
2. `doc/HelloAI_实现差距表.md`
3. `doc/HelloAI_迭代执行记录.md`
4. `doc/HelloAI_CODE_STYLE.md`
5. `doc/HelloAI_Agent接入内容生成功能开发清单_v2.0.md`

### Why These Documents Matter

- `HelloAI_项目基线文档.md`
  - Defines the current project baseline and what is already delivered versus still target-state.
- `HelloAI_实现差距表.md`
  - Defines whether a topic is already delivered, partially delivered, not delivered, or only a documentation mismatch.
- `HelloAI_迭代执行记录.md`
  - Records recent implementation rounds, actual changes, and current leftovers.
- `HelloAI_CODE_STYLE.md`
  - Defines the code style and engineering conventions that must be followed.
- `HelloAI_Agent接入内容生成功能开发清单_v2.0.md`
  - Provides project-wide development planning context, scope, and technical direction for Agent-related work.

### Mandatory Working Rules

#### 1. Clarify the task before editing

Before making changes, summarize in 3 to 5 sentences:

- what will be changed,
- why it is being changed,
- whether it is a real feature gap, skeleton work, a bug fix, or only a documentation correction,
- and what is explicitly out of scope.

#### 2. Resolve document conflicts using the repository fact priority

If documents conflict, use this priority:

1. Code and runtime behavior
2. Flyway initialization scripts and database structure
3. Verification scripts and reproducible validation results
4. `doc/HelloAI_实现差距表.md`
5. `doc/HelloAI_项目基线文档.md`
6. `README.md`
7. Historical roadmap / technical plan / comparison documents

#### 3. Stay inside project technical boundaries

Do not violate these constraints:

- JDK must remain `17`
- Spring AI must stay on the current project baseline unless the user explicitly changes that decision
- Do not use historical roadmap text to override current code reality
- Do not move orchestration logic into Controller classes

#### 4. Prefer small, closed-loop implementation

For larger work, prioritize this order:

1. backend skeleton,
2. minimum verifiable path,
3. necessary tests or scripts,
4. necessary documentation backfill.

Do not expand into unrelated frontend polishing or broad documentation cleanup unless the user asks for that.

#### 5. Consider documentation backfill after changes

After implementation, check whether these need updates:

- `doc/HelloAI_实现差距表.md`
- `doc/HelloAI_迭代执行记录.md`

Update them when:

- the project baseline changes,
- a gap item is closed or changes state,
- or the current round introduces a meaningful new implementation result.

### Additional Notes

- The repository also contains a Trae rule file at `.trae/rules/执行规则.md`.
- The repository also contains local skills such as `.trae/skills/helloai-preflight/SKILL.md` and mirrored skill directories for multiple AI tools.
- If multiple instruction sources overlap, keep them consistent and prefer the stricter project-specific rule.

### One-line Requirement

In this repository, no code change should happen before checking the baseline, gap analysis, iteration record, code style, and the existing project plan.
