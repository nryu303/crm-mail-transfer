# CRM 運用マニュアル

このドキュメントは CRM システムの日常運用に必要な手順をまとめたものです。
対象サーバー: `49.212.164.254` (CentOS Stream 9)。

---

## 1. 管理画面へのアクセス

| 項目 | 値 |
|---|---|
| URL | `http://49.212.164.254/manager/login` |
| 初期ログインID | `admin` |
| 初期パスワード | `admin123` |

**初回ログイン後は必ずパスワードを変更してください**: `設定` → `管理者パスワード変更`

---

## 2. 画面構成 (サイドバー)

| メニュー | 用途 |
|---|---|
| ダッシュボード | 未読受信、今月送信数、入金状況などの概況 |
| ユーザー管理 | CRMユーザーの登録・編集・CSV取込・認証情報発行 |
| メッセージ | 送信/予約送信/返信/予約返信/ユーザー受信 の履歴タブ |
| 一斉送信 | 対象条件付きブロードキャスト、進捗確認、キャンセル |
| 入金管理 | 入金レコードの管理、ステータス更新 |
| 課金プラン | 定期課金プランの作成とユーザー割当 |
| キャリアプール | AMG登録済みキャリアアドレスの管理 |
| 設定 | リレーサーバー、定型文、返信画面、管理者パスワード |

---

## 3. 日常オペレーション

### 3.1 ユーザー登録
1. `ユーザー管理` → `新規作成`
2. メールアドレスと表示名、キャリアコードを入力
3. 登録直後にログインIDとパスワードが画面上に一度だけ表示されるので必ず控える
4. キャリアコードが設定されていれば、空きプールアドレスに自動バインドされる

### 3.2 個別メッセージ送信
1. ユーザー一覧から対象ユーザーのメールアドレスをクリック
2. `メッセージスレッド` ボタンを押す
3. 件名と本文を入力 (置き換えタグや `%reply_url%` を使用可)
4. 即時送信または予約送信日時を指定して送信

### 3.3 一斉送信
1. `一斉送信` → `新規作成`
2. タイトル・件名・本文を入力 (定型文ドロップダウンからも挿入可)
3. 対象キャリア、ステータス、1分あたり送信件数を指定
4. 予約日時が空欄なら即時開始
5. 送信後は進捗画面で5秒ごとに自動更新される状況を確認

### 3.4 入金処理
1. `入金管理` → `新規登録` で請求を作成 (PENDING)
2. 入金確認後 `入金済にする` ボタンを押すと PAID_AT が自動設定される
3. 課金プランに加入しているユーザーは、毎日深夜に自動で請求レコードが生成される

---

## 4. サーバー運用コマンド

すべて SSH ログインの上、`sudo` 権限で実行してください。

### 4.1 サービス状態確認

```bash
sudo systemctl status crm
```

`Active: active (running)` が期待される状態です。

### 4.2 再起動

```bash
sudo systemctl restart crm
```

約10秒で起動完了します。再起動中は画面が一時的に応答しません。

### 4.3 ログ確認

```bash
# リアルタイム監視
sudo journalctl -u crm -f

# 直近100行
sudo journalctl -u crm --no-pager -n 100

# エラーのみ
sudo journalctl -u crm --no-pager | grep -iE 'error|exception'
```

### 4.4 nginx 再起動 (設定変更後)

```bash
sudo nginx -t            # 構文チェック
sudo systemctl reload nginx
```

### 4.5 ファイアウォール確認

```bash
sudo firewall-cmd --list-all
```

`services: http https ssh` が許可されていること。ポート 50000 は外部から直接アクセスできないこと。

---

## 5. バックアップ

### 5.1 データベース手動バックアップ

```bash
mkdir -p ~/crm-backups
podman exec crm-mysql mysqldump -ucrm_user -pcrm_user_dev_2026 \
  --default-character-set=utf8mb4 --single-transaction crm \
  > ~/crm-backups/crm-$(date +%Y%m%d-%H%M%S).sql
```

### 5.2 自動バックアップ (毎日03時)

crontab に以下を追加:

```
0 3 * * * mkdir -p /home/centos/crm-backups && podman exec crm-mysql mysqldump -ucrm_user -pcrm_user_dev_2026 --default-character-set=utf8mb4 --single-transaction crm | gzip > /home/centos/crm-backups/crm-$(date +\%Y\%m\%d).sql.gz && find /home/centos/crm-backups -name 'crm-*.sql.gz' -mtime +14 -delete
```

14日分を保持、古いものは自動削除。

### 5.3 リストア

```bash
gunzip -c ~/crm-backups/crm-YYYYMMDD.sql.gz | podman exec -i crm-mysql mysql -ucrm_user -pcrm_user_dev_2026 crm
```

リストア前にサービスを停止することを推奨:
```bash
sudo systemctl stop crm
# リストア実行
sudo systemctl start crm
```

---

## 6. セキュリティチェックリスト

本番運用前に必ず以下を確認してください。

- [ ] 初期管理者パスワード `admin123` から別のものへ変更済み (`設定` → `管理者パスワード変更`)
- [ ] `/etc/systemd/system/crm.service` の `AES_ENCRYPTION_KEY` を強力な値に変更済み
- [ ] `/etc/nginx/conf.d/crm.conf` の `/api/inbound/receive-raw` 配下に AMG 送信元IPの `allow` / `deny all` 記述を有効化済み
- [ ] firewalld でポート 80 / 443 / 22 のみ許可、他は全て拒否
- [ ] 送信アダプタ (`app.outbound.adapter`) が用途に合う値 (`stub` / `smtp`) に設定されている
- [ ] MySQL の `crm_user` パスワードを本番値に変更 (`application.yml` or環境変数経由)
- [ ] DNS名とTLS証明書 (Let's Encrypt等) を設定

---

## 7. 送信アダプタ切替

現在の設定は `/etc/systemd/system/crm.service` 内の環境変数 `OUTBOUND_ADAPTER` で制御されます。

| 値 | 挙動 |
|---|---|
| `stub` | メールを実際に送信しない。ログに記録のみ。テスト/開発用の既定値 |
| `smtp` | キャリアプールに登録されたSMTP情報を用いて直接送信 |

変更手順:
```bash
sudo systemctl edit crm
# [Service] 配下に以下を記載して保存
#   Environment="OUTBOUND_ADAPTER=smtp"
sudo systemctl daemon-reload
sudo systemctl restart crm
```

---

## 8. よくあるトラブル

### 8.1 ログインできない
- パスワードを控え忘れた場合、SSH 経由で直接DBから再設定する必要があります:
```bash
# BCryptハッシュを生成 (jarと同じBCryptで揃える必要あり)
# または https://bcrypt-generator.com/ でコスト12のハッシュを生成
# 例: 新パスワード "NewPass2026" のハッシュを取得して:
podman exec crm-mysql mysql -ucrm_user -pcrm_user_dev_2026 crm \
  -e "UPDATE ADMIN_USER SET LOGIN_PASSWORD='生成したハッシュ' WHERE LOGIN_ID='admin'"
```

### 8.2 送信が反映されない
- `sudo journalctl -u crm --no-pager | grep -iE 'STUB MAIL|SMTP MAIL|OutboundMail'` で送信ログを確認
- スケジューラが動いているか: `sudo journalctl -u crm | grep Scheduler`
- キャリアプールが空の場合、ユーザーのバインドが失敗しています

### 8.3 画面が 500 エラーになる
- `sudo journalctl -u crm --no-pager | tail -50` でスタックトレースを確認
- 画面上の詳細は非表示になっていますが、ログには完全な情報が残っています

### 8.4 MySQL コンテナが停止している
```bash
podman ps -a | grep crm-mysql
podman start crm-mysql
```

`linger` 設定により centos ユーザーの podman は再起動後も自動起動します。

### 8.5 ディスクが一杯
```bash
df -h
# 大きいログを削除
sudo journalctl --vacuum-time=7d
# 古いバックアップを削除
find ~/crm-backups -name '*.sql.gz' -mtime +30 -delete
```

---

## 9. 連絡先

- システム開発担当: (記入してください)
- AMG管理担当: (記入してください)
- SIer緊急連絡先: (記入してください)
