# 分支保護規則建議

## main (PROD)
- Require pull request before merging
- Require approvals: 2
- Require status checks: all CI workflows must pass
- Require branches to be up to date
- Restrict pushes: only release managers
- No force pushes
- No deletions

## uat (UAT)
- Require pull request before merging
- Require approvals: 1
- Require status checks: all CI workflows must pass
- Source branches: sit only

## sit (SIT)
- Require pull request before merging
- Require approvals: 1
- Require status checks: build-test must pass
- Source branches: dev only

## dev (DEV)
- Allow direct pushes from developers
- Require status checks: build-test must pass (optional)

## 分支流程
```
feature/* → dev (PR, auto deploy DEV)
dev → sit (PR, auto deploy SIT)
sit → uat (PR, manual deploy UAT)
uat → main (PR, manual deploy PROD)
```
