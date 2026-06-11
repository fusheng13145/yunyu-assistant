$base = "c:\Users\ASUS\Desktop\云谕助手\yunyu-assistant\frontend"
$files = @(
  "$base\src\views\SmartRobot.vue",
  "$base\src\views\ChatRobot.vue",
  "$base\src\components\ChatMessages.vue",
  "$base\src\views\Login.vue",
  "$base\src\views\Register.vue",
  "$base\src\views\NotFound.vue"
)

$replacements = @{
  'style="color: var(--morandi-text-inverse){{' = 'style="color: var(--morandi-text-inverse){{'
  'style="color: var(--morandi-text-secondary){{' = 'style="color: var(--morandi-text-secondary){{'
  'style="color: var(--morandi-text-muted){{' = 'style="color: var(--morandi-text-muted){{'
  'style="color: var(--morandi-text-primary){{' = 'style="color: var(--morandi-text-primary){{'
  'style="color: var(--morandi-primary){{' = 'style="color: var(--morandi-primary){{'
  'style="color: var(--morandi-error){{' = 'style="color: var(--morandi-error){{'
  'style="color: var(--morandi-success){{' = 'style="color: var(--morandi-success){{'
  'style="color: var(--morandi-warning){{' = 'style="color: var(--morandi-warning){{'
  'style="color: var(--morandi-accent){{' = 'style="color: var(--morandi-accent){{'
  'style="color: var(--morandi-gold){{' = 'style="color: var(--morandi-gold){{'
}

foreach ($f in $files) {
  if (!(Test-Path $f)) { Write-Host "Skip (not found): $f"; continue }
  $content = Get-Content $f -Raw -Encoding UTF8
  $original = $content
  foreach ($k in $replacements.Keys) {
    $content = $content.Replace($k, $replacements[$k])
  }
  if ($content -ne $original) {
    Set-Content $f -Value $content -Encoding UTF8 -NoNewline
    Write-Host "Fixed: $f"
  } else {
    Write-Host "No changes needed: $f"
  }
}
