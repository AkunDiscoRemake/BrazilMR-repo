-- Brazil MR Developer · WINDOW · nenhuma permissão necessária.
assert(zxr.app.type == "WINDOW")
zxr.window.setTitle("Olá, espaço")

local title = zxr.ui.text {
    text = "Uma janela. Muitas possibilidades.",
    x = 0.07, y = 0.09, width = 0.88, height = 0.18,
    fontSize = 25, color = "#F0EBFF"
}
local count = 0
local button = zxr.ui.button {
    text = "Experimentar input", x = 0.07, y = 0.42,
    width = 0.72, height = 0.22, background = "#794DDA"
}
zxr.ui.on(button, "click", function()
    count = count + 1
    zxr.ui.set(title, {text = "Interações recebidas: " .. count})
end)
zxr.system.on("mode", function(mode)
    zxr.window.setTitle("Olá, espaço · " .. mode)
end)
