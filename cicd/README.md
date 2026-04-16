# CI/CD 配置

本目錄集中管理 CI/CD 相關的通用配置，供 GitHub Actions 和 GitLab CI 共用。

## 目錄結構

```
cicd/
├── envs/                    # 環境變數配置（BPMN EL 佔位符替換值）
│   ├── dev.yml
│   ├── sit.yml
│   ├── uat.yml
│   └── prod.yml
├── templates/               # MR/PR 模板（單一來源）
│   └── merge_request.md
├── .env.example             # 生產環境變數範本
├── BRANCH_PROTECTION.md     # 分支保護規則建議
└── README.md
```

## 平台特定檔案（必須留在固定路徑）

| 檔案 | 說明 |
|------|------|
| `.github/workflows/*.yml` | GitHub Actions workflows |
| `.github/pull_request_template.md` | GitHub PR 模板（引用 cicd/templates/） |
| `.gitlab-ci.yml` | GitLab CI pipeline |
| `.gitlab/merge_request_templates/Default.md` | GitLab MR 模板（引用 cicd/templates/） |

## 環境部署策略

| 分支 | 環境 | 觸發方式 |
|------|------|---------|
| dev | DEV | commit 自動部署 |
| sit | SIT | merge 自動部署 |
| uat | UAT | 人工觸發 |
| main | PROD | 人工觸發 |

## BPMN EL 佔位符

`cicd/envs/{env}.yml` 中定義的變數會在 BPMN 部署時替換：

```yaml
ENV_FINANCE_GROUP: finance_dept_approver  # → ${ENV_FINANCE_GROUP} in BPMN
```
