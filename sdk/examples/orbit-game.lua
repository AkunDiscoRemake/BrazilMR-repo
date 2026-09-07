-- GAME · scenario é opcional e requer consentimento explícito.
assert(zxr.game.isGame())
zxr.window.setTitle("Orbit · laboratório XR")
local score = 0
local label = zxr.ui.text {text = "Toque. Aponte. Explore.", x=.08, y=.12, fontSize=28}
local target = zxr.ui.button {text="+ 1 ponto", x=.25, y=.45, width=.5, height=.25}
zxr.ui.on(target, "click", function()
    score = score + 1
    zxr.ui.set(label, {text = "Pontuação: " .. score})
    zxr.ui.move(target, .1 + (score % 3) * .12, .4 + (score % 2) * .12)
end)
if zxr.system.hasPermission("scenario") then
    local object = zxr.scenario.spawn {x=0, y=.75, z=-2, size=.13, color="#B18AFF"}
    zxr.game.on("update", function(time)
        zxr.scenario.move(object, math.sin(time) * .45, .75, -2)
    end)
else
    print("scenario não concedida: jogo continua dentro da janela.")
end
