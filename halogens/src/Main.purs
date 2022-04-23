module Main where

import Prelude
import Data.NonEmpty ((:|))
import Affjax as AX
import Affjax.ResponseFormat as AXRF
import Data.Argonaut.Core (stringify)
import Data.Array (cons, concat)
import Data.Either (Either(..))
import Data.HTTP.Method as HTTPM
import Data.List.Types (List(..), NonEmptyList(..))
import Data.Maybe (Maybe(..), fromMaybe, fromJust)
import Data.Int (toStringAs, decimal)
import Effect (Effect)
import Effect.Aff (Aff, launchAff, runAff_)
import Effect.Aff.Class (class MonadAff)
import Effect.Class (class MonadEffect, liftEffect)
import Effect.Exception (Error)
import Effect.Random (random)
import Foreign (MultipleErrors, ForeignError(..))
import Halogen as H
import Halogen.Aff as HA
import Halogen.HTML as HH
import Halogen.HTML.Events as HE
import Halogen.HTML.Properties as HP
import Halogen.VDom.Driver (runUI)
import Halogen.VDom.Types as HVTypes
import Simple.JSON (readJSON)
import Web.DOM.Element (getAttribute)
import Web.DOM.ParentNode (QuerySelector(..))
import Web.HTML (window)
import Web.HTML.Common as HTMLCom
import Web.HTML.HTMLElement (toElement)
import Web.HTML.Location (replace)
import Web.HTML.Window (Window, location)

foreign import showDate :: String -> String 
foreign import weekAhead :: String -> String
foreign import monthAhead :: String -> String

data Action = Initialize | ShowWeekAhead | ShowMonthAhead

type Config = { prefix :: String, user :: Maybe String, csrf :: String, dateFrom :: String, dateUntil :: String }

type Event = 
  { id:: Int
  , owner:: String
  , startDateTime:: String
  , repeatWeeks:: Int 
  , description:: String
  , link:: String 
  , showToGroup:: Int 
  , showToAll:: Int 
  , daysTo:: Int 
  }

type State
  = { events :: Either MultipleErrors (Array Event)
    , prefix :: String
    , user :: Maybe String
    , csrf :: String
    , dateFrom :: String 
    , dateUntil :: String
    }

initialState :: forall input. Config -> input -> State
initialState conf _ = { events : Left notReady, prefix : conf.prefix, user : conf.user, csrf : conf.csrf, dateFrom : "", dateUntil : ""  } 
-- TODO: initialize dateFrom and dateUntil

component :: forall query input output m. MonadAff m => Config -> H.Component query input output m
component conf =
  H.mkComponent
    { initialState : initialState conf
    , render : render conf
    , eval : H.mkEval $ H.defaultEval { handleAction = handleAction conf, initialize = Just Initialize }
    }

render :: forall cs m. Config -> State -> H.ComponentHTML Action cs m
render conf state =     
  HH.div_ $ concat
    [ [ showEvents conf state
      ] 
    ]

getList :: forall output m. MonadAff m => Config -> H.HalogenM State Action () output m Unit
getList conf = do 
  result <- H.liftAff $ AX.request (AX.defaultRequest { 
    url = (conf.prefix <> "/list?from=" <> conf.dateFrom <> "&until=" <> conf.dateUntil), 
    method = Left HTTPM.GET, 
    responseFormat = AXRF.json 
    })
  case result of
    Left err -> H.modify_ \x -> x { events = readJSON "error" } 
    Right response -> H.modify_ \x -> x { events = readJSON $ stringify response.body } 

getWindow :: forall m . MonadAff m => m Window 
getWindow = H.liftEffect window 
win :: forall output m . MonadAff m => H.HalogenM State Action () output m Window
win = getWindow
handleAction :: forall output m . MonadAff m => Config -> Action -> H.HalogenM State Action () output m Unit
handleAction conf = case _ of
  Initialize -> getList conf 
  ShowWeekAhead  -> getWindow >>= location >>> H.liftEffect >>= replace (weekAhead conf.prefix) >>> H.liftEffect
  ShowMonthAhead -> getWindow >>= location >>> H.liftEffect >>= replace (monthAhead conf.prefix) >>> H.liftEffect

notReady :: MultipleErrors 
notReady = NonEmptyList (ForeignError "not ready" :| Nil)

mkattr:: forall a b . String -> String -> HP.IProp a b
mkattr attrname attrval = HP.attr (HTMLCom.AttrName attrname) attrval

mklink:: forall a b . String -> String -> HH.HTML a b
mklink href txt = HH.a [ mkattr "href" href ] [ HH.text txt ]

showWAIT :: forall w r . String -> HH.HTML w r 
showWAIT msg = HH.div_ [ HH.p_ [ HH.element (HVTypes.ElemName "font") [ HP.attr (HTMLCom.AttrName "size") "22" ] [HH.text $ "WAIT" <> msg ] ] ]

showEvent :: forall w r . Config -> State -> Event -> HH.HTML w r
showEvent conf state event = 
  HH.tr 
  []
  $ concat 
    [ [ HH.td 
        []
        [ HH.span 
          ( if event.daysTo < 2 
            then [ mkattr "style" "background-color:#7fff00;" ] 
            else if event.daysTo < 3 then [ mkattr "style" "background-color:#c0f0f0" ] else [ mkattr "class" "bg-light text-dark" ] 
            )
          [ HH.text $ showDate event.startDateTime ] 
        , HH.br_
        , HH.span [] [ if event.link == "" then HH.text event.description else mklink event.link event.description ] 
        ]
      ]
    , case conf.user of 
        Just u -> 
          [ HH.td 
            []
            [ HH.span [ mkattr "class" "badge badge-info" ] [ HH.text event.owner ] 
            , HH.br_ 
            , HH.span 
                  [ case event.showToAll of 
                      0 -> mkattr "class" "badge badge-danger"
                      1 -> mkattr "class" "badge badge-warning"
                      _ -> mkattr "class" "badge badge-success"
                  ] 
                  [ HH.text "world" ]
            , HH.br_
            , HH.span 
                  [ case event.showToAll of 
                      0 -> mkattr "class" "badge badge-danger"
                      1 -> mkattr "class" "badge badge-warning"
                      _ -> mkattr "class" "badge badge-success"
                  ] 
                  [ HH.text "group" ]
            ]
          , HH.td 
            []
            [ HH.form 
                    [ mkattr "action" $ state.prefix <> "editevent", mkattr "method" "get" ]
                    [ HH.input [ mkattr "type" "hidden", mkattr "name" "csrf", mkattr "value" conf.csrf ]
                    , HH.input [ mkattr "type" "hidden", mkattr "name" "id", mkattr "value" (toStringAs decimal event.id) ]
                    , HH.input [ mkattr "type" "submit", mkattr "class" "btn btn-outline-primary", mkattr "value" "Edit" ]
                    ]
            , HH.form 
                    [ mkattr "action" $ state.prefix <> "delevent", mkattr "method" "get" ]
                    [ HH.input [ mkattr "type" "hidden", mkattr "name" "csrf", mkattr "value" conf.csrf ]
                    , HH.input [ mkattr "type" "hidden", mkattr "name" "id", mkattr "value" (toStringAs decimal event.id) ]
                    , HH.input [ mkattr "type" "hidden", mkattr "name" "description", mkattr "value" event.description ]
                    , HH.input [ mkattr "type" "hidden", mkattr "name" "datetime", mkattr "value" event.startDateTime ]
                    , HH.input [ mkattr "type" "submit", mkattr "class" "btn btn-outline-danger", mkattr "value" "Delete" ]
                    ]
            ]
          ]
        Nothing -> []
    ]

showEvents ::  forall w . Config -> State  -> HH.HTML w Action 
showEvents conf state =
  HH.p_
  [ case state.user of 
      Just _ -> 
        HH.form 
        [ mkattr "action" $ state.prefix <> "newevent" ] 
        [ HH.input [ mkattr "type" "submit", mkattr "value" "Add event", mkattr "class" "btn btn-outline-dark" ] ] 
      Nothing -> HH.text ""
  , HH.table 
    []
    [ HH.tr 
      []
      [ HH.td 
        []
        [ HH.button 
          [ mkattr "class" "btn btn-outline-dark", HE.onClick (\_ -> ShowWeekAhead) ] 
          [ HH.text "week ahead"]
        ]
      , HH.td
        []
        [ HH.button 
          [ mkattr "class" "btn btn-outline-dark", HE.onClick (\_ -> ShowMonthAhead) ] 
          [ HH.text "month ahead"]
        ]
      , HH.td [] [ HH.text "       " ]
      , HH.td 
        [] 
        case state.user of 
            Just _ -> 
              [ HH.a 
                [ mkattr "href" (state.prefix <> "changepassword") ] 
                [ HH.table [] [ HH.tr [] [ HH.td [] [HH.text "change"] ], HH.tr [] [ HH.td [] [ HH.text "password" ] ] ] ]
              ]
            Nothing -> [ HH.a [ mkattr "href" $ state.prefix <> "login" ] [ HH.text "Login" ] ]
      ]
    ]
  , HH.form 
    [ mkattr "action" state.prefix, mkattr "method" "get" ] 
    [ HH.table 
      []
      [ HH.tr
        []
        [ HH.td
          []
          [ HH.text "Events from" ]
        , HH.td 
          []
          [ HH.text "until" ]
        , HH.td [] []
        ]
      , HH.tr 
        []
        [ HH.td
          []
          [ HH.input [ mkattr "type" "date", mkattr "name" "from" ] ]
        , HH.td 
          []
          [ HH.input [ mkattr "type" "date", mkattr "name" "until" ] ]
        , HH.td
          []
          [ HH.input [ mkattr "type" "submit", mkattr "value" "▶", mkattr "class" "btn btn-outline-dark" ] ]
        ]
      ]
    ]
  , case state.events of 
        Right evs -> 
          HH.table 
          [ mkattr "class" "table", mkattr "style" "max-width:fit-content;" ] 
          (map (showEvent conf state) evs)
        Left _  -> showWAIT ""
  ]
main :: Effect Unit
main = HA.runHalogenAff do
  body <- HA.awaitBody
  mconf <- HA.selectElement $ QuerySelector "#parameters"
  case mconf of 
    Just conf -> do 
      prefix <- H.liftEffect $ getAttribute "data-prefix" (toElement conf)
      user <- H.liftEffect $ getAttribute "data-user" (toElement conf)
      csrf <- H.liftEffect $ getAttribute "data-csrf" (toElement conf)
      from <- H.liftEffect $ getAttribute "data-from" (toElement conf)
      till <- H.liftEffect $ getAttribute "data-until" (toElement conf)
      runUI (component  { 
        prefix : fromMaybe "" prefix, 
        user : user, 
        csrf : fromMaybe "" csrf, 
        dateFrom : fromMaybe "" from, 
        dateUntil : fromMaybe "" till  
        }) unit body
    Nothing -> 
      runUI (component  { prefix : "", user : Nothing, csrf : "", dateFrom : "", dateUntil : "" }) unit body
