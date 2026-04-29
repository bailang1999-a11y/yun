# Project Collaboration Rules

These rules apply to the whole `xiyiyun` project unless the user explicitly overrides them in a later message.

## Workflow

- Before starting any development task, first break down the user's request and describe the intended scope.
- Do not modify files, run deployment commands, restart services, or create subagents until the user confirms with wording such as "开始", "安排", "可以做", or "确认".
- Use subagents as much as possible for implementation work. The main agent should coordinate, supervise progress, integrate results, review changes, and report back to the user.
- Keep progress updates concise and explain what is being done and why.

## Frontend And Backend Sequencing

- For frontend modification tasks, only change frontend files.
- After finishing frontend changes, stop and wait for the user's visual or functional confirmation.
- Do not start corresponding backend work until the user confirms that the frontend is acceptable.

## Browser Usage

- Do not use the in-app browser for inspection or verification unless the user explicitly asks for browser checking, preview, or testing.
- If browser verification is requested, keep it limited to the requested page or flow.

## Risky Actions

- Before restarting services, redeploying, deleting data, committing, pushing, or doing anything that may lose local in-memory data, explain the risk and wait for explicit confirmation.
- The local backend currently uses in-memory data in parts of the MVP. Restarting or recreating the backend container may reset supplier data entered through the UI.
