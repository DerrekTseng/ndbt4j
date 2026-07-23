# 測試用磁力連結

> 使用者提供的真實磁力連結，供開發過程測試（單元測試的解析案例、之後 M6/M7 的整合測試）。
> 之後有需要會再補更多組。

## 第 1 組（2026-07-23）

```
magnet:?xt=urn:btih:IF4ZTTPVIENGKIVL5M2MEBMUGSTJ2GCU
```

```
magnet:?xt=urn:btih:417999cdf5411a6522abeb34c2059434a69d1854
```

**注意**：這兩條是同一個 info-hash 的兩種編碼——
上為 Base32（32 字元），下為 hex（40 字元），
`IF4ZTTPVIENGKIVL5M2MEBMUGSTJ2GCU` (Base32) == `417999cdf5411a6522abeb34c2059434a69d1854` (hex)。

單元測試 `MagnetUriTest` 以此驗證兩種格式解析結果一致。
兩條都不帶 tr=（tracker），實際取 metadata 需靠 DHT（M7 之後才能整合測試）。
